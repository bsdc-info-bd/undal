package com.undal.syl.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The coupon rule, proven on the phone's side of the wire (V-071 … V-078).
 *
 * The three vector tables below are the contract, not decoration.
 * `tools/check-web-rules.mjs` reads every `assertDiscount(…)`, `assertCoupon(…)`
 * and `assertWindow(…)` line out of this file and replays it through the
 * Worker's `coupons.ts` and the storefront script's `undalEvaluateCoupon`, so a
 * change made here without the other two copies fails CI. On the JVM these same
 * tables are asserted against `CouponMath` — the code the Android cart runs.
 *
 * The vectors are the awkward ones on purpose: the floor of 7 %, a cap that
 * bites, free delivery on a zero basket, a window that crosses midnight, a code
 * used exactly its limit. A table of round numbers proves nothing.
 *
 * `assertCoupon` carries a coupon as a small text spec — `type=percent;value=20;
 * cap=1000;days=fri,sat;from=18:00;to=22:00;at=21:00;weekday=fri;exp=expired` —
 * because the JavaScript side of the gate has to build the same coupon from the
 * same characters, and a spec is easier to keep honest on both sides than eleven
 * positional arguments.
 */
class CouponMathTest {

    /**
     * One instant for every time-dependent vector: 2027-01-15T08:00Z, which is
     * 14:00 in Dhaka on a Friday. The gate uses the same convention (`exp=expired`
     * is one minute ago, `exp=future` an hour ahead) rather than a fixed date, so
     * the vectors cannot rot.
     */
    private val now = 1_800_000_000_000L

    // ------------------------------------------------------------------ tables

    /**
     * type · value · cap · subtotal · delivery · expected discount.
     *
     * The money itself: what a coupon is worth before any condition is judged.
     */
    private fun assertDiscount(
        type: String,
        value: Long,
        cap: Long,
        subtotal: Long,
        delivery: Long,
        expected: Long
    ) {
        assertEquals(
            "$type $value cap $cap on $subtotal + $delivery",
            expected,
            CouponMath.discountPaisa(subtotal, delivery, type, value, cap)
        )
    }

    /**
     * coupon spec · subtotal · delivery · expected reason · expected discount.
     *
     * Eligibility, including which refusal wins when two are true at once (an
     * expired coupon that is also switched off must say "switched off", because
     * that is the fact the owner can act on).
     */
    private fun assertCoupon(
        spec: String,
        subtotal: Long,
        delivery: Long,
        expectedReason: String,
        expectedDiscount: Long
    ) {
        val verdict = CouponMath.evaluate(
            couponOf(spec),
            CouponMath.Context(subtotal, delivery, clockOf(spec))
        )
        assertEquals("discount for [$spec] on $subtotal + $delivery", expectedDiscount, verdict.discountPaisa)
        assertEquals("reason for [$spec]", expectedReason, verdict.reason)
        assertEquals(
            "eligibility for [$spec]",
            expectedReason.isEmpty(),
            verdict.eligible
        )
    }

    /** from · to · minute of day · expected. A window that ends before it starts crosses midnight. */
    private fun assertWindow(from: String, to: String, minutes: Int, expected: Boolean) {
        assertEquals(
            "window $from–$to at minute $minutes",
            expected,
            CouponMath.withinDailyWindow(from, to, minutes)
        )
    }

    // ----------------------------------------------------------- the money table

    @Test
    fun theDiscountTableDecidesEveryKindOfCoupon() {
        assertDiscount("percent", 20, 0, 25_000, 0, 5_000)
        assertDiscount("percent", 20, 1_000, 25_000, 0, 1_000)
        assertDiscount("percent", 7, 0, 1_999, 0, 139)
        assertDiscount("percent", 100, 0, 12_345, 0, 12_345)
        assertDiscount("percent", 150, 0, 10_000, 0, 10_000)
        assertDiscount("percent", 20, 0, 0, 4_500, 0)
        assertDiscount("fixed", 5_000, 0, 25_000, 0, 5_000)
        assertDiscount("fixed", 5_000, 0, 3_000, 0, 3_000)
        assertDiscount("fixed", 5_000, 1_000, 25_000, 0, 1_000)
        assertDiscount("fixed", 0, 0, 25_000, 0, 0)
        assertDiscount("freedelivery", 0, 0, 25_000, 4_500, 4_500)
        assertDiscount("freedelivery", 0, 0, 25_000, 0, 0)
        assertDiscount("freedelivery", 0, 1_000, 25_000, 4_500, 1_000)
        assertDiscount("freedelivery", 0, 0, 0, 4_500, 4_500)
        assertDiscount("bogus", 500, 0, 25_000, 0, 0)
    }

    // ------------------------------------------------------ the eligibility table

    @Test
    fun theEligibilityTableDecidesEveryRefusal() {
        assertCoupon("type=percent;value=20", 25_000, 0, "", 5_000)
        assertCoupon("type=percent;value=20;active=0", 25_000, 0, "switched off", 0)
        assertCoupon("type=percent;value=20;exp=expired", 25_000, 0, "expired", 0)
        assertCoupon("type=percent;value=20;exp=future", 25_000, 0, "", 5_000)
        assertCoupon("type=percent;value=20;days=fri, sat;weekday=wed", 25_000, 0, "not today", 0)
        assertCoupon("type=percent;value=20;days=fri;weekday=fri", 25_000, 0, "", 5_000)
        assertCoupon("type=percent;value=20;from=18:00;to=22:00;at=17:59", 25_000, 0, "outside its hours", 0)
        assertCoupon("type=percent;value=20;from=18:00;to=22:00;at=18:00", 25_000, 0, "", 5_000)
        assertCoupon("type=percent;value=20;from=20:00;to=01:00;at=00:30", 25_000, 0, "", 5_000)
        assertCoupon("type=percent;value=20;from=20:00;to=01:00;at=12:00", 25_000, 0, "outside its hours", 0)
        assertCoupon("type=percent;value=20;uses=5;used=5", 25_000, 0, "used up", 0)
        assertCoupon("type=percent;value=20;uses=5;used=4", 25_000, 0, "", 5_000)
        assertCoupon("type=percent;value=20;min=30000", 25_000, 0, "basket too small", 0)
        assertCoupon("type=percent;value=20;min=25000", 25_000, 0, "", 5_000)
        assertCoupon("type=fixed;value=0", 25_000, 0, "changes nothing here", 0)
        assertCoupon("type=bogus;value=50", 25_000, 0, "not set up correctly", 0)
        assertCoupon("type=freedelivery", 25_000, 4_500, "", 4_500)
        assertCoupon("type=freedelivery;cap=1000", 25_000, 4_500, "", 1_000)
    }

    // ------------------------------------------------------------ the window table

    @Test
    fun theWindowTableHandlesMidnightAndMissingEnds() {
        assertWindow("18:00", "22:00", 18 * 60, true)
        assertWindow("18:00", "22:00", 21 * 60 + 59, true)
        assertWindow("18:00", "22:00", 22 * 60, false)
        assertWindow("18:00", "", 17 * 60 + 59, false)
        assertWindow("18:00", "", 18 * 60, true)
        assertWindow("", "22:00", 21 * 60 + 59, true)
        assertWindow("", "22:00", 22 * 60, false)
        assertWindow("20:00", "01:00", 23 * 60, true)
        assertWindow("20:00", "01:00", 30, true)
        assertWindow("20:00", "01:00", 12 * 60, false)
        assertWindow("09:00", "09:00", 9 * 60, true)
        assertWindow("", "", 0, true)
    }

    // --------------------------------------------------------- app-only behaviour

    @Test
    fun theBestCouponIsTheOneThatSavesMostAndDiesSoonest() {
        val soon = CouponMath.Coupon(code = "soon", type = "fixed", value = 5_000, active = true, expiresAtMillis = now + 60_000L)
        val later = CouponMath.Coupon(code = "later", type = "fixed", value = 5_000, active = true, expiresAtMillis = now + 86_400_000L)
        val small = CouponMath.Coupon(code = "small", type = "fixed", value = 1_000, active = true)

        val best = CouponMath.pickBest(
            listOf(small, later, soon),
            CouponMath.Context(25_000, 0, CouponMath.Clock("wed", 12 * 60, now))
        )
        // The code that comes back is the document's identity, not the spelling
        // the kitchen typed: coupons live at `coupons/{CODE}`, Firestore stores
        // that path upper case, and the order has to name the document it used.
        assertEquals("SOON", best?.code)
        assertEquals(5_000L, best?.discountPaisa)

        // So a coupon saved in lower case still applies, and still names `EID20`.
        val typedLowerCase = CouponMath.Coupon(code = "eid20", type = "percent", value = 10, active = true)
        assertEquals(
            "EID20",
            CouponMath.pickBest(
                listOf(typedLowerCase),
                CouponMath.Context(25_000, 0, CouponMath.Clock("wed", 12 * 60, now))
            )?.code
        )

        // Nothing helps this basket, so nothing is applied: an automatic discount
        // that silently failed is worse than none.
        assertNull(
            CouponMath.pickBest(
                listOf(CouponMath.Coupon(code = "big", type = "fixed", value = 5_000, active = true, minSubtotalAmountPaisa = 99_000)),
                CouponMath.Context(25_000, 0, CouponMath.Clock("wed", 12 * 60, now))
            )
        )
    }

    @Test
    fun aCodeOnlyCouponIsNeverAppliedByItself() {
        val codeOnly = CouponMath.Coupon(code = "SECRET", type = "fixed", value = 9_000, active = true, autoApply = false)
        val context = CouponMath.Context(25_000, 0, CouponMath.Clock("wed", 12 * 60, now))
        assertNull(CouponMath.pickBest(listOf(codeOnly), context))
        // Typed in, it works exactly as designed.
        assertTrue(CouponMath.evaluate(codeOnly, context).eligible)
    }

    @Test
    fun headlinesAndConditionsReadLikeMoney() {
        assertEquals("Free delivery", CouponMath.headline(CouponMath.Coupon(type = "freedelivery")))
        assertEquals("Free delivery, up to ৳ 100", CouponMath.headline(CouponMath.Coupon(type = "freedelivery", maxDiscountPaisa = 10_000)))
        assertEquals("20% off", CouponMath.headline(CouponMath.Coupon(type = "percent", value = 20)))
        assertEquals("20% off, up to ৳ 100", CouponMath.headline(CouponMath.Coupon(type = "percent", value = 20, maxDiscountPaisa = 10_000)))
        assertEquals("৳ 50 off", CouponMath.headline(CouponMath.Coupon(type = "fixed", value = 5_000)))
        assertEquals(
            "On orders over ৳ 250 · 18:00–22:00 · fri",
            CouponMath.conditionLine(
                CouponMath.Coupon(
                    type = "percent",
                    value = 20,
                    minSubtotalAmountPaisa = 25_000,
                    startsAt = "18:00",
                    endsAt = "22:00",
                    daysOfWeek = listOf("fri")
                )
            )
        )
    }

    @Test
    fun theCouponClockIsAlwaysDhaka() {
        // 2026-10-14T18:30Z is 00:30 the next morning in Dhaka, and still Thursday.
        val clock = CouponMath.Clock.dhaka(
            nowMillis = ZonedDateTime.of(2026, 10, 14, 18, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(),
            offsetMinutes = CouponMath.DHAKA_OFFSET_MINUTES
        )
        assertEquals("thu", clock.weekday)
        assertEquals(30, clock.minutes)
    }

    @Test
    fun aTimestampIsReadFromEveryShapeFirestoreSends() {
        assertEquals(0L, CouponMath.expiryMillis(null))
        assertEquals(1_700_000_000_000L, CouponMath.expiryMillis(mapOf("seconds" to 1_700_000_000L)))
        assertEquals(1_700_000_000_000L, CouponMath.expiryMillis(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, CouponMath.expiryMillis("2023-11-14T22:13:20Z"))
    }

    // ------------------------------------------------------------------ the spec

    /** Reads one `key=value` out of a spec, or "". */
    private fun specValue(spec: String, key: String): String =
        spec.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { it.substringBefore("=").trim() == key }
            ?.substringAfter("=", "")
            ?.trim()
            .orEmpty()

    /**
     * Builds a coupon from the spec. The same grammar, and the same meanings, are
     * implemented in `tools/check-web-rules.mjs`.
     */
    private fun couponOf(spec: String): CouponMath.Coupon {
        var coupon = CouponMath.Coupon(
            code = specValue(spec, "code").ifEmpty { "TEST" },
            type = specValue(spec, "type").ifEmpty { "percent" },
            value = specValue(spec, "value").toLongOrNull() ?: 20L,
            active = specValue(spec, "active") != "0",
            minSubtotalAmountPaisa = specValue(spec, "min").toLongOrNull() ?: 0L,
            maxDiscountPaisa = specValue(spec, "cap").toLongOrNull() ?: 0L,
            usageLimit = specValue(spec, "uses").toLongOrNull() ?: 0L,
            usedCount = specValue(spec, "used").toLongOrNull() ?: 0L,
            startsAt = specValue(spec, "from"),
            endsAt = specValue(spec, "to"),
            daysOfWeek = specValue(spec, "days").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        )
        coupon = when (specValue(spec, "exp")) {
            "expired" -> coupon.copy(expiresAtMillis = now - 60_000L)
            "future" -> coupon.copy(expiresAtMillis = now + 3_600_000L)
            else -> coupon
        }
        return coupon
    }

    /** The clock half of the spec: `weekday=wed;at=21:30`. */
    private fun clockOf(spec: String): CouponMath.Clock = CouponMath.Clock(
        weekday = specValue(spec, "weekday").ifEmpty { "wed" },
        minutes = CouponMath.parseMinutesOfDay(specValue(spec, "at")) ?: (12 * 60),
        now = now
    )

    @Test
    fun theSpecItselfIsReadCorrectly() {
        val coupon = couponOf("type=fixed;value=500;cap=1000;min=25000;uses=3;used=1;days=fri,sat;from=18:00;to=22:00;exp=future")
        assertEquals("fixed", coupon.type)
        assertEquals(500L, coupon.value)
        assertEquals(1_000L, coupon.maxDiscountPaisa)
        assertEquals(25_000L, coupon.minSubtotalAmountPaisa)
        assertEquals(3L, coupon.usageLimit)
        assertEquals(1L, coupon.usedCount)
        assertEquals(listOf("fri", "sat"), coupon.daysOfWeek)
        assertEquals("18:00", coupon.startsAt)
        assertEquals(now + 3_600_000L, coupon.expiresAtMillis)
        assertTrue(coupon.active)
        assertTrue(coupon.autoApply)

        val clock = clockOf("weekday=fri;at=21:30")
        assertEquals("fri", clock.weekday)
        assertEquals(21 * 60 + 30, clock.minutes)
        assertEquals(now, clock.now)
    }

    @Test
    fun anOffSpecStillProducesAUsableCoupon() {
        // A malformed spec must not silently become a free-everything coupon.
        assertFalse(couponOf("active=0").active)
        assertEquals(12 * 60, clockOf("").minutes)
    }
}
