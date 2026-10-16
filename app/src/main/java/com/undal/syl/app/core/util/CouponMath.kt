package com.undal.syl.app.core.util
import java.util.Locale

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The coupon rule, in Kotlin — the twin of `worker/src/web/coupons.ts`.
 *
 * One discount rule, three implementations: this one (the Android cart and
 * checkout), the Worker's `coupons.ts` (which prices every order before it is
 * written) and the storefront script's `undalEvaluateCoupon` (which shows the
 * customer what will happen). Any two of them disagreeing is a customer quoted
 * one price and charged another, so `tools/check-web-rules.mjs` reads the vector
 * tables out of `CouponMathTest` and replays every row through both JavaScript
 * copies; this file is what those vectors are *for*.
 *
 * What the kitchen can build (V-071 … V-078):
 *
 *   · **percent** — 20 % off, optionally capped ("20 % off, up to ৳100"), which
 *     is how a discount stops a ৳4,000 party order from giving away ৳800;
 *   · **fixed** — ৳50 off;
 *   · **freedelivery** — the delivery fee is on the kitchen, the single most
 *     effective coupon when delivery is priced per kilometre;
 *   · a minimum food subtotal, a usage limit, an expiry and an optional daily
 *     window (18:00–22:00) or weekday set ("Friday only"), judged in Asia/Dhaka
 *     where the kitchen actually cooks;
 *   · **autoApply** — published and applied to any basket it helps, with no code
 *     typed at all;
 *   · a banner in the app and on the web store, because a discount nobody knows
 *     about is a discount nobody uses.
 */
object CouponMath {

    const val TYPE_PERCENT = "percent"
    const val TYPE_FIXED = "fixed"
    const val TYPE_FREE_DELIVERY = "freedelivery"

    val WEEKDAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

    /** Asia/Dhaka is UTC+6 and has never observed daylight saving. */
    const val DHAKA_OFFSET_MINUTES = 360

    /**
     * A coupon document, flattened. Every field is optional because coupons
     * written before this engine existed still have to behave.
     */
    data class Coupon(
        val code: String = "",
        val type: String = "",
        val value: Long = 0L,
        val active: Boolean = false,
        val description: String = "",
        val minSubtotalAmountPaisa: Long = 0L,
        /** Ceiling on the discount, in paisa. Zero means "no ceiling". */
        val maxDiscountPaisa: Long = 0L,
        /** Epoch millis, or 0 when the coupon never expires. */
        val expiresAtMillis: Long = 0L,
        /** "HH:MM", inclusive start of the daily window. */
        val startsAt: String = "",
        /** "HH:MM", exclusive end. An end before the start crosses midnight. */
        val endsAt: String = "",
        val daysOfWeek: List<String> = emptyList(),
        val usageLimit: Long = 0L,
        val usedCount: Long = 0L,
        /** Applied by itself, with no code typed. Missing means yes, deliberately. */
        val autoApply: Boolean = true,
        /** Shown as a banner on the web store. */
        val showOnWeb: Boolean = true,
        val bannerUrl: String = ""
    ) {

        /** The normalised type, or null when the document is malformed. */
        val kind: String? get() = typeOf(type)

        /** "20% off, up to ৳ 100" · "৳ 50 off" · "Free delivery". */
        val headline: String get() = CouponMath.headline(this)
    }

    /** The clock the coupon windows are judged against. */
    data class Clock(
        /** "sun" … "sat". */
        val weekday: String,
        /** Minutes since midnight. */
        val minutes: Int,
        /** The moment itself, so an expiry can be compared without a second clock. */
        val now: Long
    ) {

        companion object {

            /** The Dhaka clock. Bangladesh has no DST, so the offset is arithmetic. */
            fun dhaka(nowMillis: Long = System.currentTimeMillis(), offsetMinutes: Int = DHAKA_OFFSET_MINUTES): Clock {
                val shifted = Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
                return Clock(
                    weekday = WEEKDAYS[shifted.dayOfWeek.value % 7],
                    minutes = shifted.hour * 60 + shifted.minute,
                    now = nowMillis
                )
            }

            /** A clock for a specific Dhaka wall time — what the tests use. */
            fun of(local: ZonedDateTime, nowMillis: Long = local.toInstant().toEpochMilli()): Clock =
                Clock(
                    weekday = WEEKDAYS[local.dayOfWeek.value % 7],
                    minutes = local.hour * 60 + local.minute,
                    now = nowMillis
                )
        }
    }

    /** Everything a verdict needs to know about the basket. */
    data class Context(
        val subtotalPaisa: Long,
        val deliveryPaisa: Long,
        val clock: Clock = Clock.dhaka()
    )

    data class Verdict(
        val eligible: Boolean,
        /** Why not, in words a person can act on. Empty when it is eligible. */
        val reason: String,
        val discountPaisa: Long
    )

    data class Best(
        val code: String,
        val type: String,
        val description: String,
        val discountPaisa: Long
    )

    // ---------------------------------------------------------------- parsing

    fun typeOf(value: Any?): String? = when (value?.toString()?.trim()?.lowercase(Locale.ENGLISH)) {
        TYPE_PERCENT -> TYPE_PERCENT
        TYPE_FIXED -> TYPE_FIXED
        TYPE_FREE_DELIVERY -> TYPE_FREE_DELIVERY
        else -> null
    }

    /**
     * Whatever Firestore handed back for a timestamp → epoch millis.
     *
     * Every shape the one field `coupons.expiresAt` can arrive in, on every
     * surface that judges it:
     *   · a `com.google.firebase.Timestamp` — what the Android SDK puts in
     *     `document.data`, and what `snapshot.getTimestamp()` returns;
     *   · a plain `java.util.Date`;
     *   · a number of millis — the board row's `expiresAtMillis`;
     *   · an ISO-8601 string — what Firestore REST returns for `timestampValue`,
     *     which is what the Worker and the storefront script see;
     *   · a `{seconds, nanos}` map — a timestamp that crossed a JSON boundary.
     *
     * The `Timestamp` branch is the whole reason this function is written out
     * long. Without it the `else` below answered `0`, and `0` means "this coupon
     * never expires" — so the phone accepted a code the kitchen had already dated
     * out, while the Worker reading the *same document* over REST refused it as
     * expired. One field, two verdicts, and the customer finds out at the
     * checkout with the word "Expired" on a promotion they were told was live.
     */
    fun expiryMillis(value: Any?): Long = when (value) {
        null -> 0L
        is com.google.firebase.Timestamp -> value.toDate().time
        is java.util.Date -> value.time.coerceAtLeast(0L)
        is Number -> numberMillis(value.toDouble())
        is String -> textMillis(value.trim())
        is Map<*, *> -> ((value["seconds"] ?: value["_seconds"]) as? Number)
            ?.toLong()
            ?.takeIf { it > 0L }
            ?.times(1_000L)
            ?: 0L
        else -> 0L
    }

    /**
     * Below this a number is epoch *seconds*, not milliseconds: 1e11 ms is March
     * 1973, 1e11 s is the year 5138. A coupon dated by hand in the Firebase
     * console as a number of seconds was read as milliseconds — January 1970 —
     * and refused to every customer as expired. `couponExpiryMillis` in the Worker
     * applies the same ceiling.
     */
    private const val EPOCH_SECONDS_CEILING = 100_000_000_000.0

    private val DIGITS_ONLY = Regex("^\\d+(\\.\\d+)?$")
    private val DATE_ONLY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    private fun numberMillis(number: Double): Long = when {
        number.isNaN() || number <= 0.0 -> 0L
        number < EPOCH_SECONDS_CEILING -> Math.round(number * 1_000.0)
        else -> Math.round(number)
    }

    /**
     * Text, in every form a person or a REST call writes it: a number, a bare
     * date — the whole of that day in Dhaka, the 23:59:59 the editor stores — or
     * an ISO-8601 instant with `Z` or an offset. Anything else is 0: no expiry.
     */
    private fun textMillis(text: String): Long {
        if (text.isEmpty()) return 0L
        if (DIGITS_ONLY.matches(text)) return numberMillis(text.toDouble())
        val dhaka = ZoneOffset.ofTotalSeconds(DHAKA_OFFSET_MINUTES * 60)
        if (DATE_ONLY.matches(text)) {
            return runCatching {
                java.time.LocalDate.parse(text).plusDays(1).atStartOfDay(dhaka).toInstant().toEpochMilli() - 1_000L
            }.getOrDefault(0L)
        }
        return runCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .getOrDefault(0L)
            .coerceAtLeast(0L)
    }

    private val EXPIRY_MOMENT = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

    /**
     * "16 Oct 2026, 11:59 PM" in Dhaka — the date *and* the minute a coupon ends,
     * or "" when it has no end. What a refusal prints: the date alone cannot
     * settle "it is not expired", because an expiry stored at 06:00 and one stored
     * at 23:59 print the same date. `couponExpiryMoment` in the Worker prints the
     * same characters.
     */
    fun expiryMoment(expiresAtMillis: Long): String =
        if (expiresAtMillis <= 0L) {
            ""
        } else {
            Instant.ofEpochMilli(expiresAtMillis)
                .atOffset(ZoneOffset.ofTotalSeconds(DHAKA_OFFSET_MINUTES * 60))
                .format(EXPIRY_MOMENT)
        }

    private val HH_MM = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    /**
     * "HH:MM" → minutes since midnight, or null when it is not a clock time.
     *
     * The digits may be in any script — the kitchen's phone writes ০৯:০০ for what
     * the schedule means as 09:00 — and a single-digit hour ("9:30") is a time a
     * person typed, not a mistake to refuse.
     */
    fun parseMinutesOfDay(value: Any?): Int? {
        val raw = (value as? String)?.trim() ?: return null
        val text = UndalDigits.toAscii(raw)
        val match = HH_MM.matchEntire(text) ?: return null
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    /** The weekday set, normalised to "mon"…"sun", de-duplicated, order kept. */
    fun days(value: Any?): List<String> {
        val raw = value as? List<*> ?: return emptyList()
        val seen = mutableListOf<String>()
        raw.forEach { entry ->
            if (entry !is String) return@forEach
            val day = entry.trim().lowercase(Locale.ENGLISH).take(3)
            if (day in WEEKDAYS && day !in seen) seen.add(day)
        }
        return seen
    }

    /**
     * Is a minute inside a window? A window that ends before it starts crosses
     * midnight (20:00 → 01:00), which is what a late-night kitchen means by it.
     */
    fun withinDailyWindow(startsAt: Any?, endsAt: Any?, minutes: Int): Boolean {
        val start = parseMinutesOfDay(startsAt)
        val end = parseMinutesOfDay(endsAt)
        if (start == null && end == null) return true
        if (start != null && end == null) return minutes >= start
        if (start == null && end != null) return minutes < end
        if (start != null && start == end) return true
        if (start != null && end != null) {
            return if (end > start) {
                minutes >= start && minutes < end
            } else {
                // A window that ends before it starts crosses midnight.
                minutes >= start || minutes < end
            }
        }
        return true
    }

    // --------------------------------------------------------------- the money

    /**
     * What the coupon is worth on this basket, before any conditions.
     *
     * Identical arithmetic to `couponDiscountPaisa` in the Worker and
     * `discountOf` in the storefront script.
     */
    fun discountPaisa(
        subtotalPaisa: Long,
        deliveryPaisa: Long,
        type: String?,
        value: Long,
        maxDiscountPaisa: Long = 0L
    ): Long {
        val subtotal = subtotalPaisa.coerceAtLeast(0L)
        val delivery = deliveryPaisa.coerceAtLeast(0L)
        val cap = if (maxDiscountPaisa > 0L) maxDiscountPaisa else 0L
        // Free delivery is worth the fee — and the cap still caps it, because it
        // is the owner saying "this never costs me more than X".
        if (type == TYPE_FREE_DELIVERY) return if (cap > 0L) minOf(delivery, cap) else delivery
        if (subtotal <= 0L) return 0L
        var discount = when (type) {
            TYPE_PERCENT -> {
                val percent = value.coerceIn(0L, 100L)
                subtotal * percent / 100L
            }
            TYPE_FIXED -> value.coerceAtLeast(0L)
            else -> 0L
        }
        if (cap > 0L) discount = minOf(discount, cap)
        return discount.coerceIn(0L, subtotal)
    }

    /**
     * Is this coupon usable right now for this basket, and what is it worth?
     *
     * The order of the checks is the order a customer experiences them: is it
     * switched on, is it set up, is it in season, is it the right day and time of
     * day, has it been used up, does the basket qualify. Each refusal carries the
     * sentence that tells the truth about *this* basket.
     */
    fun evaluate(coupon: Coupon, context: Context): Verdict {
        if (!coupon.active) return Verdict(false, "switched off", 0L)
        val type = coupon.kind ?: return Verdict(false, "not set up correctly", 0L)

        if (coupon.expiresAtMillis > 0L && coupon.expiresAtMillis <= context.clock.now) {
            return Verdict(false, "expired", 0L)
        }

        val days = coupon.daysOfWeek.map { it.trim().lowercase(Locale.ENGLISH).take(3) }.filter { it in WEEKDAYS }
        if (days.isNotEmpty() && days.none { it == context.clock.weekday.take(3) }) {
            return Verdict(false, "not today", 0L)
        }
        if (!withinDailyWindow(coupon.startsAt, coupon.endsAt, context.clock.minutes)) {
            return Verdict(false, "outside its hours", 0L)
        }

        if (coupon.usageLimit >= 1L && coupon.usedCount >= coupon.usageLimit) {
            return Verdict(false, "used up", 0L)
        }

        if (coupon.minSubtotalAmountPaisa > 0L && context.subtotalPaisa < coupon.minSubtotalAmountPaisa) {
            return Verdict(false, "basket too small", 0L)
        }

        val discount = discountPaisa(
            subtotalPaisa = context.subtotalPaisa,
            deliveryPaisa = context.deliveryPaisa,
            type = type,
            value = coupon.value,
            maxDiscountPaisa = coupon.maxDiscountPaisa
        )
        if (discount <= 0L) return Verdict(false, "changes nothing here", 0L)
        return Verdict(true, "", discount)
    }

    /**
     * The coupon the checkout applies by itself: the one that saves the most.
     *
     * A tie is broken by the soonest expiry — a code that dies tonight is worth
     * using before one that lives until December. Null means "no coupon helps
     * this basket", and the basket is priced without one, which is the honest
     * outcome: an automatic discount that silently failed is worse than none.
     *
     * Code-only coupons (`autoApply = false`) are skipped here on purpose: they
     * exist to be typed, and the board shows them for exactly that.
     */
    fun pickBest(coupons: List<Coupon>, context: Context): Best? {
        var best: Best? = null
        var bestExpiry = Long.MAX_VALUE
        for (coupon in coupons) {
            if (coupon.autoApply.not()) continue
            val code = coupon.code.trim().uppercase(Locale.ENGLISH)
            if (code.isEmpty()) continue
            val verdict = evaluate(coupon, context)
            if (!verdict.eligible) continue
            val expiry = if (coupon.expiresAtMillis > 0L) coupon.expiresAtMillis else Long.MAX_VALUE
            val beats = best == null ||
                verdict.discountPaisa > best.discountPaisa ||
                (verdict.discountPaisa == best.discountPaisa && expiry < bestExpiry)
            if (!beats) continue
            best = Best(
                code = code,
                type = coupon.kind ?: TYPE_FIXED,
                description = coupon.description,
                discountPaisa = verdict.discountPaisa
            )
            bestExpiry = expiry
        }
        return best
    }

    // -------------------------------------------------------------- the wording

    /** "20% off, up to ৳ 100" · "৳ 50 off" · "Free delivery, up to ৳ 100". */
    fun headline(coupon: Coupon): String =
        headline(coupon.kind, coupon.value, coupon.maxDiscountPaisa, coupon.description)

    fun headline(type: String?, value: Long, maxDiscountPaisa: Long = 0L, description: String = ""): String =
        when (type) {
            TYPE_FREE_DELIVERY -> if (maxDiscountPaisa > 0L) {
                "Free delivery, up to ${Money.format(maxDiscountPaisa)}"
            } else {
                "Free delivery"
            }
            TYPE_PERCENT -> buildString {
                append(value.coerceIn(0L, 100L))
                append("% off")
                if (maxDiscountPaisa > 0L) append(", up to ").append(Money.format(maxDiscountPaisa))
            }
            TYPE_FIXED -> "${Money.format(value)} off"
            else -> description
        }

    /**
     * The line under the headline: "On orders over ৳ 250 · 18:00–22:00 · fri".
     * Empty when the coupon has no conditions worth saying out loud.
     */
    fun conditionLine(coupon: Coupon): String {
        val parts = mutableListOf<String>()
        if (coupon.minSubtotalAmountPaisa > 0L) {
            parts.add("On orders over ${Money.format(coupon.minSubtotalAmountPaisa)}")
        }
        val start = parseMinutesOfDay(coupon.startsAt)
        val end = parseMinutesOfDay(coupon.endsAt)
        if (start != null || end != null) {
            val from = coupon.startsAt.trim().ifEmpty { "00:00" }
            val to = coupon.endsAt.trim().ifEmpty { "24:00" }
            parts.add("$from–$to")
        }
        if (coupon.daysOfWeek.isNotEmpty()) {
            parts.add(coupon.daysOfWeek.joinToString(", ") { it.trim().lowercase(Locale.ENGLISH).take(3) })
        }
        if (coupon.maxDiscountPaisa > 0L && coupon.kind != TYPE_PERCENT && coupon.kind != TYPE_FREE_DELIVERY) {
            parts.add("up to ${Money.format(coupon.maxDiscountPaisa)}")
        }
        return parts.joinToString(" · ")
    }
}
