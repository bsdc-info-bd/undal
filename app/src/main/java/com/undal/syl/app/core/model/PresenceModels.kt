package com.undal.syl.app.core.model

import com.undal.syl.app.core.util.TimeDhaka
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime


/**
 * One day of the kitchen phone's connection, as the Worker's presence sweep
 * recorded it (`presenceDays/{yyyy-mm-dd}`, Asia/Dhaka).
 *
 * The sweep runs every five minutes and writes one sample per run: online or
 * offline, plus a window per outage and the moment the store was paused and
 * restored. Five-minute samples over a day are enough to answer the owner's real
 * question — how many minutes tonight was my phone unreachable — without a
 * single byte of per-second telemetry.
 */
data class PresenceDay(
    /** `yyyy-mm-dd`, Asia/Dhaka. */
    val dateKey: String,
    val samplesOnline: Int,
    val samplesOffline: Int,
    val autoOfflineCount: Int,
    val restoredCount: Int,
    val outages: List<PresenceOutage>,
    val lastSeenMillis: Long,
    val lastOfflineAtMillis: Long,
    val lastOnlineAtMillis: Long
) {

    /** Minutes this day's samples stand for, at five minutes each. */
    val onlineMinutes: Int get() = samplesOnline * PresenceSummary.SAMPLE_MINUTES

    val offlineMinutes: Int get() = samplesOffline * PresenceSummary.SAMPLE_MINUTES

    val sampledMinutes: Int get() = onlineMinutes + offlineMinutes

    /** 0…100, rounded to one decimal. `null` before anything was sampled. */
    val uptimePercent: Double?
        get() = if (sampledMinutes == 0) null else ((onlineMinutes * 1000.0) / sampledMinutes) / 10.0

    val longestOutageMinutes: Int
        get() = outages.maxOfOrNull { it.minutes } ?: 0

    companion object {
        fun fromMap(dateKey: String, data: Map<String, Any?>): PresenceDay {
            val outages = (data["outages"] as? List<*>).orEmpty()
                .mapNotNull { entry -> PresenceOutage.fromEntry(entry) }
                .sortedBy { it.fromMillis }
            return PresenceDay(
                dateKey = dateKey,
                samplesOnline = intOf(data["samplesOnline"]),
                samplesOffline = intOf(data["samplesOffline"]),
                autoOfflineCount = intOf(data["autoOfflineCount"]),
                restoredCount = intOf(data["restoredCount"]),
                outages = outages,
                lastSeenMillis = millisOf(data["lastSeen"]),
                lastOfflineAtMillis = millisOf(data["lastOfflineAt"]),
                lastOnlineAtMillis = millisOf(data["lastOnlineAt"])
            )
        }

        private fun intOf(value: Any?): Int = when (value) {
            is Number -> value.toInt().coerceAtLeast(0)
            is String -> value.toIntOrNull()?.coerceAtLeast(0) ?: 0
            else -> 0
        }

        private fun millisOf(value: Any?): Long = when (value) {
            is Number -> value.toLong()
            is com.google.firebase.Timestamp -> value.seconds * 1000L
            is String -> parseMillis(value)
            else -> 0L
        }
    }
}

/** One stretch of time this phone could not be reached. */
data class PresenceOutage(
    val fromMillis: Long,
    /** Zero while the outage is still open — the phone has not come back yet. */
    val toMillis: Long,
    val minutes: Int
) {

    val open: Boolean get() = toMillis <= 0L

    val fromLabel: String get() = TimeDhaka.wallClock(fromMillis)

    val toLabel: String get() = if (open) "now" else TimeDhaka.wallClock(toMillis)

    val span: String
        get() = if (open) "$fromLabel → still offline" else "$fromLabel → $toLabel · $minutes min"

    companion object {
        private fun millisOf(value: Any?): Long = when (value) {
            is Number -> value.toLong()
            is com.google.firebase.Timestamp -> value.seconds * 1000L
            is String -> parseMillis(value)
            else -> 0L
        }

        fun fromEntry(entry: Any?): PresenceOutage? {
            val map = entry as? Map<*, *> ?: return null
            val from = millisOf(map["from"])
            if (from <= 0L) return null
            val to = millisOf(map["to"])
            val minutes = when (val raw = map["minutes"]) {
                is Number -> raw.toInt()
                else -> if (to > from) ((to - from) / 60_000L).toInt() else 0
            }
            return PresenceOutage(fromMillis = from, toMillis = to, minutes = minutes.coerceAtLeast(0))
        }
    }
}

/**
 * Seven days of connection, summed for the dashboard.
 *
 * Everything here is derived, never stored: the Worker keeps the samples, the
 * app keeps the arithmetic, and the two cannot disagree because there is only
 * one copy of the raw numbers.
 */
data class PresenceSummary(
    val days: List<PresenceDay> = emptyList(),
    val heartbeatAgeMillis: Long = -1L,
    /** True once at least one day has been read, so a first paint is not "0 %". */
    val loaded: Boolean = false
) {

    /** Today, in Dhaka terms: the first entry of the newest-first list. */
    val today: PresenceDay? get() = days.firstOrNull { it.dateKey == TimeDhaka.todayKey() }

    val onlineMinutes: Int get() = days.sumOf { it.onlineMinutes }

    val offlineMinutes: Int get() = days.sumOf { it.offlineMinutes }

    val sampledMinutes: Int get() = onlineMinutes + offlineMinutes

    val uptimePercent: Double?
        get() = if (sampledMinutes == 0) null else ((onlineMinutes * 1000.0) / sampledMinutes) / 10.0

    val pauses: Int get() = days.sumOf { it.autoOfflineCount }

    val restores: Int get() = days.sumOf { it.restoredCount }

    val longestOutageMinutes: Int get() = days.maxOfOrNull { it.longestOutageMinutes } ?: 0

    val outageCount: Int get() = days.sumOf { it.outages.size }

    /** The most recent outage, open or closed, for the one-line story. */
    val lastOutage: PresenceOutage? = days.flatMap { it.outages }.maxByOrNull { it.fromMillis }

    val heartbeatLabel: String
        get() = if (heartbeatAgeMillis < 0L) "never" else com.undal.syl.app.core.background.PresenceHeartbeat.describeAge(heartbeatAgeMillis)

    val heartbeatHealthy: Boolean
        get() = heartbeatAgeMillis in 0..(com.undal.syl.app.core.background.PresenceHeartbeat.STALE_AGE_MILLIS)

    /** What a person reads: "99.6 %" or "—" before there is anything to say. */
    val uptimeLabel: String
        get() = uptimePercent?.let { percent -> formatPercent(percent) } ?: "—"

    /** Offline time in words, e.g. "12 min today · 1 h 5 min this week". */
    val offlineLine: String
        get() {
            val todayLine = today?.let { day ->
                if (day.offlineMinutes == 0) "none today" else "${formatMinutes(day.offlineMinutes)} today"
            } ?: "no samples yet"
            if (offlineMinutes == 0) return todayLine
            return "$todayLine · ${formatMinutes(offlineMinutes)} in 7 days"
        }

    val outageLine: String
        get() {
            if (days.isEmpty()) return "The kitchen's connection history appears after the first five-minute check-in."
            if (outageCount == 0) return "No outages recorded in the last seven days."
            val last = lastOutage
            val story = if (last == null) "" else " Last: ${last.span}."
            return "$outageCount outage(s) in 7 days · longest ${formatMinutes(longestOutageMinutes)}.$story"
        }

    companion object {
        /** The sweep's own cadence, so minutes and samples can never drift apart. */
        const val SAMPLE_MINUTES = 5

        fun of(days: List<PresenceDay>, heartbeatAgeMillis: Long): PresenceSummary =
            PresenceSummary(
                days = days.sortedByDescending { it.dateKey },
                heartbeatAgeMillis = heartbeatAgeMillis,
                loaded = true
            )

        fun formatPercent(percent: Double): String {
            val rounded = Math.round(percent * 10.0) / 10.0
            val whole = rounded.toInt()
            val tenth = Math.round((rounded - whole) * 10.0).toInt()
            return if (tenth == 0) "$whole %" else "$whole.$tenth %"
        }

        fun formatMinutes(minutes: Int): String {
            if (minutes <= 0) return "0 min"
            if (minutes < 60) return "$minutes min"
            val hours = minutes / 60
            val rest = minutes % 60
            return if (rest == 0) "${hours} h" else "${hours} h ${rest} min"
        }
    }
}

/**
 * A timestamp that arrived as text → epoch millis.
 *
 * Firestore hands a REST-style ISO string back for anything it cannot decode as a
 * number, and the three spellings it can arrive in — a plain instant, an offset, a
 * zone — are all valid ISO-8601. Tried in that order; a value nobody can read
 * becomes 0 rather than a crash on the vendor's dashboard.
 */
private fun parseMillis(text: String): Long {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0L
    return runCatching { Instant.parse(trimmed).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        .recoverCatching { ZonedDateTime.parse(trimmed).toInstant().toEpochMilli() }
        .getOrDefault(0L)
}
