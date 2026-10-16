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
 *
 * Whether the store is taking orders is [StoreHours]' question, not a field: the
 * owner's switch ([switchOn]), the watchdog's pause ([autoClosed]) and the hours.
 */
data class StoreConfig(
    val storeName: String,
    /**
     * The effective state — "taking orders right now" — and the only field older
     * builds read. The Worker's presence cron keeps it in step with the hours, so
     * those builds keep the schedule too. Not the switch: see [switchOn].
     */
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
    val updatedAt: Timestamp? = null,
    /**
     * The owner's switch, when a schedule-aware build recorded it: on means "take
     * orders whenever my hours say so", off means "paused until I say otherwise".
     * Null on a document no such build has written yet.
     */
    val acceptingOrders: Boolean? = null,
    /**
     * The value of [open] the last schedule-aware writer set. When [open] no
     * longer matches it, an older build pressed Open or Close since, and that
     * press is the owner's latest word.
     */
    val openSynced: Boolean? = null
) {

    /**
     * The owner's switch — mirrored by `switchOn` in `worker/src/util/store-hours.ts`.
     *
     * [acceptingOrders] when it was recorded, unless an older build has written
     * [open] by hand since; on a document from before the switch existed, [open]
     * was the switch.
     */
    val switchOn: Boolean
        get() {
            val recorded = acceptingOrders ?: return open
            val legacyWrite = openSynced != null && openSynced != open
            return if (legacyWrite) open else recorded
        }

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
                updatedAt = map["updatedAt"] as? Timestamp,
                acceptingOrders = map["acceptingOrders"] as? Boolean,
                openSynced = map["openSynced"] as? Boolean
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
                // `closed: true` is what this app writes; `mode: "closed"` is the
                // older spelling the Worker has always honoured. Both are a day off.
                closed = map["closed"] == true || map["mode"] == "closed",
                slots = StoreSlot.listFrom(map["slots"]),
                note = (map["note"] as? String).orEmpty()
            )
        }
    }
}

/**
 * The live answer to "can this customer order right now?" (C-022 · C-023).
 *
 * The watchdog's pause first, the owner's switch second, the hours third — and
 * all three must agree before anybody can order. The hours used to be advice:
 * a switched-on store said "Open outside the published hours — the kitchen is
 * cooking right now anyway", around the clock, and took the orders. Now a store
 * whose switch is on opens and closes itself on its schedule ([StoreHours]),
 * and a store whose switch is off is paused until the owner turns it back on.
 */
data class StoreOpenState(
    val serviceable: Boolean,
    val headline: String,
    val detail: String,
    val todayLine: String,
    /** "Opens tomorrow at 9:00 AM" while closed; "Open until 10:00 PM" while open. */
    val nextOpeningLine: String,
    val reason: Reason = if (serviceable) Reason.OPEN else Reason.PAUSED,
    /** The owner's switch: on = the store follows its hours by itself; off = paused. */
    val switchOn: Boolean = serviceable
) {

    /** Why the store is open or closed — what the owner's screens explain. */
    enum class Reason {
        OPEN,
        /** The watchdog paused it: the kitchen phone stopped checking in. */
        AUTO_CLOSED,
        /** The owner turned the switch off. */
        PAUSED,
        /** The kitchen pin is not published, so no fee can be measured. */
        SETTING_UP,
        /** The switch is on, but no opening hours are published at all. */
        NO_HOURS,
        /** The switch is on and the hours say closed: it opens by itself later. */
        OUTSIDE_HOURS
    }

    val closed: Boolean get() = !serviceable

    /** Closed only by the clock — the switch is on, and the store opens by itself. */
    val opensByItself: Boolean get() = reason == Reason.OUTSIDE_HOURS

    companion object {

        fun of(config: StoreConfig, now: java.time.ZonedDateTime = TimeDhaka.now()): StoreOpenState {
            val local = now.withZoneSameInstant(TimeDhaka.zone)
            val today = local.toLocalDate()
            val special = StoreHours.specialOn(config, today)
            val slots = StoreHours.slotsOn(config, today).filter { StoreHours.window(it) != null }
            val dayOffNote = special?.note?.takeIf { it.isNotBlank() }
            val todayLine = when {
                special?.closed == true -> "Closed today" + dayOffNote?.let { " · $it" }.orEmpty()
                slots.isEmpty() -> "No service hours published for today"
                else -> slots.joinToString(" · ") { it.pretty() }
            }
            val next = StoreHours.nextOpening(config, local)
            val opensLine = next?.let { "Opens ${it.label}" } ?: "Opening hours are being republished"

            val (reason, headline, detail) = when {
                config.autoClosed -> Triple(
                    Reason.AUTO_CLOSED,
                    "Closed · kitchen offline",
                    "The kitchen phone went quiet, so Undal closed the store automatically to protect you " +
                        "from orders nobody can cook. It reopens as soon as the kitchen is back online."
                )

                !config.switchOn -> Triple(
                    Reason.PAUSED,
                    "Closed",
                    config.manualOverrideNote.ifBlank { "The kitchen has paused orders for now." }
                )

                !config.hasPublishedPin -> Triple(
                    Reason.SETTING_UP,
                    "Closed · setting up",
                    "The kitchen is finishing its setup. Ordering opens very soon."
                )

                !StoreHours.hasAnyHours(config) -> Triple(
                    Reason.NO_HOURS,
                    "Closed",
                    "The kitchen has not published its opening hours yet."
                )

                !StoreHours.isOpenAt(config, local) -> Triple(
                    Reason.OUTSIDE_HOURS,
                    "Closed now",
                    buildString {
                        if (special?.closed == true) {
                            append("Closed today")
                            dayOffNote?.let { append(" — ").append(it) }
                            append(". ")
                        }
                        append(
                            next?.let { "We open ${it.label}. Your cart is kept until then." }
                                ?: "Please check back later."
                        )
                    }
                )

                else -> Triple(Reason.OPEN, "Open now", "Cooking today: $todayLine")
            }

            val serviceable = reason == Reason.OPEN
            val nextOpeningLine = if (serviceable) {
                StoreHours.closingLabel(config, local)?.let { "Open until $it" } ?: opensLine
            } else {
                opensLine
            }

            return StoreOpenState(
                serviceable = serviceable,
                headline = headline,
                detail = detail,
                todayLine = todayLine,
                nextOpeningLine = nextOpeningLine,
                reason = reason,
                switchOn = config.switchOn
            )
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
