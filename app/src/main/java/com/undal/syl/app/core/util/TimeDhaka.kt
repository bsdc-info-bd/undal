package com.undal.syl.app.core.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every clock decision in Undal happens in Asia/Dhaka.
 *
 * Bangladesh has never used daylight saving, so this zone is a fixed +06:00
 * offset and can be relied on for store schedules, cron alignment, prep
 * countdowns and order timestamps. `java.time` on API 24/25 is supplied by core
 * library desugaring (configured in app/build.gradle.kts).
 */
object TimeDhaka {

    val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val WEEKDAY_KEYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun now(): ZonedDateTime = ZonedDateTime.now(zone)

    fun today(): LocalDate = LocalDate.now(zone)

    fun fromInstant(instant: Instant): ZonedDateTime = instant.atZone(zone)

    /** "mon" … "sun", matching the `config/store.weekly` keys. */
    fun weekdayKey(date: LocalDate = today()): String = WEEKDAY_KEYS[date.dayOfWeek.value - 1]

    /** "2026-10-13", matching the `specialDates[].date` format. */
    fun dateKey(date: LocalDate = today()): String = date.toString()

    fun minutesOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

    fun minutesNow(): Int = minutesOfDay(now().toLocalTime())

    /**
     * "09:00", "9:00", "09:00:00" or "০৯:০০" → a time of day; null for anything else.
     *
     * `LocalTime.parse` alone refused a single-digit hour and Bengali digits, so a
     * slot typed that way vanished from the phone's schedule while the Worker
     * still honoured it — two surfaces, two different opening hours.
     */
    fun parseHhMm(value: String): LocalTime? {
        val match = HH_MM.matchEntire(UndalDigits.toAscii(value.trim())) ?: return null
        val hours = match.groupValues[1].toInt()
        val minutes = match.groupValues[2].toInt()
        if (hours > 23 || minutes > 59) return null
        return LocalTime.of(hours, minutes)
    }

    private val HH_MM = Regex("^(\\d{1,2}):(\\d{2})(?::\\d{2})?$")

    fun minutesFromHhMm(value: String): Int? = parseHhMm(value)?.let(::minutesOfDay)

    /** "09:00" → "9:00 AM", the form the owner and the customer both read. */
    fun prettyHhMm(value: String): String =
        parseHhMm(value)?.format(timeFormatter) ?: value

    /** "Mon", "Tue" … for the schedule screens and the next-opening line. */
    fun weekdayLabel(date: LocalDate): String = when (WEEKDAY_KEYS[date.dayOfWeek.value - 1]) {
        "mon" -> "Monday"
        "tue" -> "Tuesday"
        "wed" -> "Wednesday"
        "thu" -> "Thursday"
        "fri" -> "Friday"
        "sat" -> "Saturday"
        else -> "Sunday"
    }

    /** True when `minutes` falls inside a slot, handling windows past midnight. */
    fun isWithinSlot(openMinutes: Int, closeMinutes: Int, minutes: Int): Boolean {
        if (openMinutes == closeMinutes) return false
        return if (closeMinutes > openMinutes) {
            minutes >= openMinutes && minutes < closeMinutes
        } else {
            minutes >= openMinutes || minutes < closeMinutes
        }
    }

    /** "2026-10-14" for the day in Dhaka right now — the presence log's key. */
    fun todayKey(): String = dateKey(today())

    /** A wall clock reading from epoch millis: "8:34 PM". */
    fun wallClock(millis: Long): String =
        if (millis <= 0L) "—" else formatInstant(Instant.ofEpochMilli(millis))

    fun formatInstant(instant: Instant?): String =
        instant?.let { fromInstant(it).format(timeFormatter) } ?: "—"

    fun formatInstantDate(instant: Instant?): String =
        instant?.let { fromInstant(it).format(dateTimeFormatter) } ?: "—"

    fun formatDate(date: LocalDate): String = date.format(dateFormatter)

    /** Local label used on order cards: "2 min ago", "just now", "in 4 min". */
    fun relativeFromNow(instant: Instant?): String {
        if (instant == null) return "—"
        val seconds = Duration.between(instant, Instant.now()).seconds
        return when {
            seconds < 0 -> "in ${(-seconds) / 60 + 1} min"
            seconds < 45 -> "just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }

    /** Countdown text for prep timers: "07:32" or "OVERDUE 01:10". */
    fun countdown(prepEndsAt: Instant?, now: Instant = Instant.now()): String {
        if (prepEndsAt == null) return "—"
        val delta = Duration.between(now, prepEndsAt).seconds
        val overdue = delta < 0
        val absolute = kotlin.math.abs(delta)
        val minutes = absolute / 60
        val seconds = absolute % 60
        val body = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        return if (overdue) "OVERDUE $body" else body
    }

    fun isPrepOverdue(prepEndsAt: Instant?, now: Instant = Instant.now()): Boolean =
        prepEndsAt != null && now.isAfter(prepEndsAt)
}
