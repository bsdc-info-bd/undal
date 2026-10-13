package com.undal.syl.app.core.model

import com.google.firebase.Timestamp
import com.undal.syl.app.core.util.Distance
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.TimeDhaka
import java.time.LocalDate

/**
 * `config/store` — the kitchen's identity, hours and delivery economics.
 *
 * The document is world-readable and vendor-writable, so a customer app reads it
 * live: when the owner closes the store from the kitchen, every open customer app
 * shows Closed within a second, with the note the owner wrote.
 */
data class StoreConfig(
    val storeName: String,
    val open: Boolean,
    val autoClosed: Boolean,
    val manualOverrideNote: String,
    val weekly: Map<String, List<StoreSlot>>,
    val specialDates: List<StoreSpecialDate>,
    val deliveryPerKmAmountPaisa: Long,
    val radiusKm: Double,
    val minOrderAmountPaisa: Long,
    val storeLat: Double,
    val storeLng: Double,
    val storeAddressText: String,
    val bannerUrl: String,
    val logoUrl: String,
    val updatedAt: Timestamp? = null
) {

    /** True when the kitchen pin has been published (not the 0,0 placeholder seed). */
    val hasPublishedPin: Boolean
        get() = !(storeLat == 0.0 && storeLng == 0.0)

    fun distanceFrom(lat: Double, lng: Double): Double? =
        Distance.distanceFromStore(storeLat, storeLng, lat, lng)

    fun deliveryFeeFor(distanceKm: Double): Long =
        Money.deliveryFeePaisa(distanceKm, deliveryPerKmAmountPaisa)

    fun isServiceable(distanceKm: Double?): Boolean =
        distanceKm != null && Distance.isWithinRadius(distanceKm, radiusKm)

    companion object {
        /** Used before the store document has loaded, and if it is missing entirely. */
        fun unloaded(): StoreConfig = StoreConfig(
            storeName = "Undal HomeKitchen",
            open = false,
            autoClosed = false,
            manualOverrideNote = "The kitchen is not accepting orders right now.",
            weekly = emptyMap(),
            specialDates = emptyList(),
            deliveryPerKmAmountPaisa = 2000,
            radiusKm = 0.0,
            minOrderAmountPaisa = 15000,
            storeLat = 0.0,
            storeLng = 0.0,
            storeAddressText = "Sylhet",
            bannerUrl = "",
            logoUrl = ""
        )

        fun fromMap(map: Map<String, Any?>): StoreConfig {
            val weeklyRaw = map["weekly"] as? Map<*, *>
            val weekly = buildMap {
                weeklyRaw?.forEach { (key, value) ->
                    val day = key as? String ?: return@forEach
                    put(day, StoreSlot.listFrom(value))
                }
            }
            val specialsRaw = map["specialDates"] as? List<*>
            val specials = specialsRaw.orEmpty().mapNotNull { entry ->
                (entry as? Map<*, *>)?.let { StoreSpecialDate.fromMap(it) }
            }
            return StoreConfig(
                storeName = (map["storeName"] as? String).orEmpty().ifBlank { "Undal HomeKitchen" },
                open = map["open"] as? Boolean ?: false,
                autoClosed = map["autoClosed"] as? Boolean ?: false,
                manualOverrideNote = (map["manualOverrideNote"] as? String).orEmpty(),
                weekly = weekly,
                specialDates = specials,
                deliveryPerKmAmountPaisa = (map["deliveryPerKmAmountPaisa"] as? Number)?.toLong() ?: 2000L,
                radiusKm = (map["radiusKm"] as? Number)?.toDouble() ?: 0.0,
                minOrderAmountPaisa = (map["minOrderAmountPaisa"] as? Number)?.toLong() ?: 15000L,
                storeLat = (map["storeLat"] as? Number)?.toDouble() ?: 0.0,
                storeLng = (map["storeLng"] as? Number)?.toDouble() ?: 0.0,
                storeAddressText = (map["storeAddressText"] as? String).orEmpty(),
                bannerUrl = (map["bannerUrl"] as? String).orEmpty(),
                logoUrl = (map["logoUrl"] as? String).orEmpty(),
                updatedAt = map["updatedAt"] as? Timestamp
            )
        }
    }
}

/** One opening window, "HH:mm" in Asia/Dhaka. A day may hold more than one. */
data class StoreSlot(val open: String, val close: String) {

    val openMinutes: Int? get() = TimeDhaka.minutesFromHhMm(open)

    val closeMinutes: Int? get() = TimeDhaka.minutesFromHhMm(close)

    /** "9:00 AM – 10:00 PM", the form the owner and the customer both read. */
    fun pretty(): String = "${TimeDhaka.prettyHhMm(open)} – ${TimeDhaka.prettyHhMm(close)}"

    fun contains(minutes: Int): Boolean {
        val from = openMinutes ?: return false
        val to = closeMinutes ?: return false
        return TimeDhaka.isWithinSlot(from, to, minutes)
    }

    companion object {
        fun listFrom(value: Any?): List<StoreSlot> = when (value) {
            is List<*> -> value.mapNotNull { entry ->
                (entry as? Map<*, *>)?.let {
                    val open = it["open"] as? String
                    val close = it["close"] as? String
                    if (open != null && close != null) StoreSlot(open, close) else null
                }
            }

            else -> emptyList()
        }
    }
}

/** A dated exception: a closed day, or different hours for one date. */
data class StoreSpecialDate(
    val date: String,
    val closed: Boolean,
    val slots: List<StoreSlot>,
    val note: String = ""
) {
    companion object {
        fun fromMap(map: Map<*, *>): StoreSpecialDate? {
            val date = map["date"] as? String ?: return null
            return StoreSpecialDate(
                date = date,
                closed = map["closed"] as? Boolean ?: false,
                slots = StoreSlot.listFrom(map["slots"]),
                note = (map["note"] as? String).orEmpty()
            )
        }
    }
}

/**
 * The live answer to "can this customer order right now?" (C-022 · C-023).
 *
 * Manual switch first, auto-offline second, schedule third — because the person
 * running the kitchen is the authority, and the schedule is what the app shows
 * when nobody has touched anything.
 */
data class StoreOpenState(
    val serviceable: Boolean,
    val headline: String,
    val detail: String,
    val todayLine: String,
    val nextOpeningLine: String
) {

    val closed: Boolean get() = !serviceable

    companion object {

        fun of(config: StoreConfig, now: java.time.ZonedDateTime = TimeDhaka.now()): StoreOpenState {
            val today = now.toLocalDate()
            val dateKey = TimeDhaka.dateKey(today)
            val weekdayKey = TimeDhaka.weekdayKey(today)
            val minutesNow = TimeDhaka.minutesOfDay(now.toLocalTime())

            val special = config.specialDates.firstOrNull { it.date == dateKey }
            val slots = when {
                special == null -> config.weekly[weekdayKey].orEmpty()
                special.closed -> emptyList()
                else -> special.slots
            }
            val todayLine = when {
                special?.closed == true -> "Closed today" + special.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                slots.isEmpty() -> "No service hours published for today"
                else -> slots.joinToString(" · ") { it.pretty() }
            }
            val insideHours = slots.any { it.contains(minutesNow) }

            // The next opening, searched over the coming week.
            val nextOpeningLine = nextOpening(config, now)

            val (serviceable, headline, detail) = when {
                config.autoClosed -> Triple(
                    false,
                    "Closed · kitchen offline",
                    "The kitchen phone went quiet, so Undal closed the store automatically to protect you " +
                        "from orders nobody can cook. It reopens as soon as the kitchen is back online."
                )

                !config.open -> Triple(
                    false,
                    "Closed",
                    config.manualOverrideNote.ifBlank { "The kitchen has closed the store for now." }
                )

                !config.hasPublishedPin -> Triple(
                    false,
                    "Closed · setting up",
                    "The kitchen is finishing its setup. Ordering opens very soon."
                )

                else -> Triple(
                    true,
                    "Open now",
                    if (insideHours) {
                        "Cooking today: $todayLine"
                    } else {
                        "Open outside the published hours — the kitchen is cooking right now anyway."
                    }
                )
            }

            return StoreOpenState(
                serviceable = serviceable,
                headline = headline,
                detail = detail,
                todayLine = todayLine,
                nextOpeningLine = nextOpeningLine
            )
        }

        private fun nextOpening(config: StoreConfig, now: java.time.ZonedDateTime): String {
            val minutesNow = TimeDhaka.minutesOfDay(now.toLocalTime())
            for (offset in 0..7) {
                val date = now.toLocalDate().plusDays(offset.toLong())
                val special = config.specialDates.firstOrNull { it.date == TimeDhaka.dateKey(date) }
                if (special?.closed == true) continue
                val slots = when {
                    special != null -> special.slots
                    else -> config.weekly[TimeDhaka.weekdayKey(date)].orEmpty()
                }
                val candidate = slots
                    .mapNotNull { slot -> slot.openMinutes?.let { it to slot } }
                    .filter { (openMinutes, _) -> offset > 0 || openMinutes > minutesNow }
                    .minByOrNull { it.first }
                    ?: continue
                val dayLabel = when (offset) {
                    0 -> "today"
                    1 -> "tomorrow"
                    else -> TimeDhaka.weekdayLabel(date)
                }
                return "Opens $dayLabel at ${TimeDhaka.prettyHhMm(candidate.second.open)}"
            }
            return "Opening hours are being republished"
        }
    }
}

/** `config/promos` — the daily broadcast, also used for the home offer strip. */
data class PromosConfig(
    val enabled: Boolean,
    val slot: String,
    val lastIndex: Int,
    val messages: List<PromoMessage>
) {
    data class PromoMessage(val title: String, val body: String, val imageUrl: String)

    companion object {
        fun fromMap(map: Map<String, Any?>): PromosConfig {
            val raw = map["messages"] as? List<*>
            val messages = raw.orEmpty().mapNotNull { entry ->
                val item = entry as? Map<*, *> ?: return@mapNotNull null
                val title = item["title"] as? String ?: return@mapNotNull null
                PromoMessage(
                    title = title,
                    body = (item["body"] as? String).orEmpty(),
                    imageUrl = (item["imageUrl"] as? String).orEmpty()
                )
            }
            return PromosConfig(
                enabled = map["enabled"] as? Boolean ?: false,
                slot = (map["slot"] as? String).orEmpty().ifBlank { "18:00" },
                lastIndex = (map["lastIndex"] as? Number)?.toInt() ?: 0,
                messages = messages
            )
        }
    }
}

/** `campaigns/{id}` — a dated offer or banner the kitchen is running. */
data class Campaign(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: String,
    val value: Long,
    val startAt: Timestamp?,
    val endAt: Timestamp?,
    val bannerUrl: String,
    val code: String,
    val active: Boolean = true
) {

    fun isLive(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!active) return false
        val start = startAt?.toDate()?.time ?: return false
        val end = endAt?.toDate()?.time ?: return false
        return nowMillis in start..end
    }

    /** "25 % off", "৳ 100 off" — the words the customer sees on the hero. */
    fun valueLabel(): String = when (type) {
        "percent" -> "${value.coerceIn(0, 100)} % off"
        else -> "${Money.format(value)} off"
    }

    companion object {
        fun fromDocument(id: String, map: Map<String, Any?>): Campaign? {
            val title = map["title"] as? String ?: return null
            return Campaign(
                id = id,
                title = title,
                subtitle = (map["subtitle"] as? String).orEmpty(),
                type = (map["type"] as? String).orEmpty().ifBlank { "percent" },
                value = (map["value"] as? Number)?.toLong() ?: 0L,
                startAt = map["startAt"] as? Timestamp,
                endAt = map["endAt"] as? Timestamp,
                bannerUrl = (map["bannerUrl"] as? String).orEmpty(),
                code = (map["code"] as? String).orEmpty(),
                active = map["active"] as? Boolean ?: true
            )
        }
    }
}

/** True when the given date is the kitchen's today (used by the schedule screen). */
fun isToday(date: LocalDate): Boolean = date == TimeDhaka.today()
