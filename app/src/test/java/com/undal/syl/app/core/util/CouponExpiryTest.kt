package com.undal.syl.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * "The web says expired, but it's not."
 *
 * A coupon's expiry reaches the phone in more shapes than the editor writes,
 * because a person can also type one into the Firebase console. Each shape here
 * is one that used to be read wrongly — as "no expiry" (live forever) or as a
 * date in January 1970 (expired forever) — and `worker/test/coupons.test.ts`
 * holds `couponExpiryMillis` to the same answers.
 *
 * The refusal prints the minute as well as the day, so an owner who is sure the
 * code is live can read what the database actually holds.
 */
class CouponExpiryTest {

    /** 23:59:59 on 16 October 2026 in Dhaka — the editor's "expires 16 Oct". */
    private val endOf16OctDhaka = Instant.parse("2026-10-16T17:59:59Z").toEpochMilli()

    @Test
    fun epochSecondsAreNotMistakenForMillisecondsIn1970() {
        assertEquals(endOf16OctDhaka, CouponMath.expiryMillis(endOf16OctDhaka / 1_000L))
        assertEquals(endOf16OctDhaka, CouponMath.expiryMillis((endOf16OctDhaka / 1_000L).toInt().toDouble()))
        assertEquals(endOf16OctDhaka, CouponMath.expiryMillis(endOf16OctDhaka))
    }

    @Test
    fun aNumberWrittenAsTextIsStillANumber() {
        assertEquals(endOf16OctDhaka, CouponMath.expiryMillis((endOf16OctDhaka / 1_000L).toString()))
        assertEquals(endOf16OctDhaka, CouponMath.expiryMillis(endOf16OctDhaka.toString()))
    }

    @Test
    fun aBareDateIsTheWholeOfThatDayInDhaka() {
        assertEquals(endOf16OctDhaka, CouponMath.expiryMillis("2026-10-16"))
    }

    @Test
    fun theAdminSdkSpellingAndIsoOffsetsAreRead() {
        assertEquals(endOf16OctDhaka, CouponMath.expiryMillis(mapOf("_seconds" to endOf16OctDhaka / 1_000L, "_nanoseconds" to 0)))
        assertEquals(endOf16OctDhaka, CouponMath.expiryMillis("2026-10-16T23:59:59+06:00"))
        assertEquals(endOf16OctDhaka, CouponMath.expiryMillis("2026-10-16T17:59:59.000000Z"))
    }

    @Test
    fun nothingReadableIsNoExpiryNeverAnInventedDate() {
        listOf<Any?>(null, 0, -5, 0L, "", "soon", emptyMap<String, Any>(), Double.NaN).forEach { nothing ->
            assertEquals(nothing.toString(), 0L, CouponMath.expiryMillis(nothing))
        }
    }

    @Test
    fun aCouponDatedInSecondsForTomorrowIsLiveToday() {
        val now = endOf16OctDhaka - 86_400_000L
        val coupon = CouponMath.Coupon(
            code = "SAVE10",
            type = "percent",
            value = 10,
            active = true,
            expiresAtMillis = CouponMath.expiryMillis(endOf16OctDhaka / 1_000L)
        )
        val verdict = CouponMath.evaluate(
            coupon,
            CouponMath.Context(subtotalPaisa = 50_000, deliveryPaisa = 0, clock = CouponMath.Clock.dhaka(now))
        )
        assertTrue(verdict.reason, verdict.eligible)
        val afterwards = CouponMath.evaluate(
            coupon,
            CouponMath.Context(subtotalPaisa = 50_000, deliveryPaisa = 0, clock = CouponMath.Clock.dhaka(endOf16OctDhaka + 1_000L))
        )
        assertFalse(afterwards.eligible)
        assertEquals("expired", afterwards.reason)
    }

    @Test
    fun aRefusalPrintsTheMinuteSoItCanBeSettledByReadingIt() {
        assertEquals("16 Oct 2026, 11:59 PM", CouponMath.expiryMoment(endOf16OctDhaka))
        assertEquals("16 Oct 2026, 6:00 AM", CouponMath.expiryMoment(Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()))
        assertEquals("17 Oct 2026, 12:00 AM", CouponMath.expiryMoment(endOf16OctDhaka + 1_000L))
        assertEquals("", CouponMath.expiryMoment(0L))
    }
}
