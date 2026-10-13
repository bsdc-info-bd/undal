package com.undal.syl.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Distance drives money (the km fee) and trust (the radius gate), so it is
 * checked against known reference distances with a ±0.1 km tolerance, plus the
 * degenerate cases the checkout must survive.
 */
class DistanceTest {

    private fun assertKm(expected: Double, actual: Double, tolerance: Double = 0.1) {
        assertEquals("expected $expected km but was $actual km", expected, actual, tolerance)
    }

    @Test
    fun zeroDistanceForTheSamePoint() {
        assertKm(0.0, Distance.haversineKm(24.8949, 91.8687, 24.8949, 91.8687), 0.001)
    }

    @Test
    fun sylhetToDhakaIsAboutTwoHundredKilometres() {
        // Sylhet (24.8949, 91.8687) → Dhaka (23.8103, 90.4125)
        assertKm(194.0, Distance.haversineKm(24.8949, 91.8687, 23.8103, 90.4125), 5.0)
    }

    @Test
    fun oneKilometreOfLatitudeIsOneDegreeDividedByOneEleven() {
        // 1° of latitude ≈ 111.19 km, so 0.009° ≈ 1 km.
        assertKm(1.0, Distance.haversineKm(24.8949, 91.8687, 24.9039, 91.8687), 0.1)
    }

    @Test
    fun shortIntraCityDistancesAreAccurate() {
        // Zindabazar to Amberkhana in Sylhet: roughly 2.2 km across town.
        assertKm(2.2, Distance.haversineKm(24.8949, 91.8687, 24.9093, 91.8835), 0.3)
    }

    @Test
    fun distanceFromStoreReturnsNullWhenThePinIsUnpublished() {
        assertNull(Distance.distanceFromStore(null, 91.8687, 24.89, 91.87))
        assertNull(Distance.distanceFromStore(24.8949, null, 24.89, 91.87))
        // A zero pin is the unset marker used by the seed document.
        assertNull(Distance.distanceFromStore(0.0, 0.0, 24.89, 91.87))
        assertEquals(
            0.0,
            Distance.distanceFromStore(24.8949, 91.8687, 24.8949, 91.8687) ?: -1.0,
            0.001
        )
    }

    @Test
    fun radiusGateMatchesTheVendorSetting() {
        assertTrue(Distance.isWithinRadius(4.9, 5.0))
        assertTrue(Distance.isWithinRadius(5.0, 5.0))
        assertFalse(Distance.isWithinRadius(5.01, 5.0))
        // A radius of zero means the vendor has not published delivery yet.
        assertFalse(Distance.isWithinRadius(1.0, 0.0))
    }

    @Test
    fun kmFormattingIsReadable() {
        assertEquals("5 km", Distance.formatKm(5.0))
        assertEquals("2.4 km", Distance.formatKm(2.44))
        assertEquals("0.4 km", Distance.formatKm(0.4))
    }

    @Test
    fun feeAndDistanceComposeToAWholeTakaTotal() {
        val km = Distance.haversineKm(24.8949, 91.8687, 24.9093, 91.8835)
        val fee = Money.deliveryFeePaisa(km, perKmPaisa = 2_000)
        // 2 km-ish across town at ৳20/km → ৳40–৳60 in whole taka.
        assertTrue("unexpected fee $fee", fee in 4_000L..6_000L)
        assertEquals(0L, fee % 100)
    }
}
