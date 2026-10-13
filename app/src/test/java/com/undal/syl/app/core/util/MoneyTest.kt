package com.undal.syl.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Money maths must be exact. These tests encode the pricing rules the security
 * rules also enforce, so a regression here fails CI before a customer ever sees
 * a wrong total.
 */
class MoneyTest {

    @Test
    fun formatsWholeTakaWithoutDecimals() {
        assertEquals("৳ 250", Money.format(25_000))
        assertEquals("৳ 0", Money.format(0))
    }

    @Test
    fun groupsThousandTakaInTheLakhPattern() {
        assertEquals("৳ 1,250", Money.format(125_000))
        assertEquals("৳ 12,500", Money.format(1_250_000))
    }

    @Test
    fun keepsPaisaOnlyWhenTheyExist() {
        assertEquals("৳ 250.50", Money.format(25_050))
        assertEquals("৳ 250.05", Money.format(25_005))
    }

    @Test
    fun parsesVendorInputIncludingSymbolAndCommas() {
        assertEquals(25_000L, Money.parseTakaToPaisa("250"))
        assertEquals(25_050L, Money.parseTakaToPaisa("250.50"))
        assertEquals(125_000L, Money.parseTakaToPaisa("৳ 1,250"))
        assertNull(Money.parseTakaToPaisa(""))
        assertNull(Money.parseTakaToPaisa("abc"))
        assertNull(Money.parseTakaToPaisa("-5"))
    }

    @Test
    fun parseAndFormatRoundTrip() {
        val values = listOf(0L, 100L, 25_000L, 25_050L, 1_250_000L)
        values.forEach { paisa ->
            val printed = Money.format(paisa).removePrefix("৳ ").replace(",", "")
            assertEquals(paisa, Money.parseTakaToPaisa(printed))
        }
    }

    @Test
    fun deliveryFeeBillsWholeKilometresRoundedUp() {
        // The single rule of the platform: ceil(km) × per-km rate.
        assertEquals(2000L, Money.deliveryFeePaisa(0.4, 2000))
        assertEquals(2000L, Money.deliveryFeePaisa(1.0, 2000))
        assertEquals(4000L, Money.deliveryFeePaisa(1.2, 2000))
        assertEquals(6000L, Money.deliveryFeePaisa(2.01, 2000))
        assertEquals(0L, Money.deliveryFeePaisa(0.0, 2000))
        assertEquals(0L, Money.deliveryFeePaisa(Double.NaN, 2000))
    }

    @Test
    fun lineTotalsAreExactIntegerProducts() {
        assertEquals(44_900L, Money.lineTotal(22_450, 2))
        assertEquals(0L, Money.lineTotal(22_450, 0))
    }

    @Test
    fun grandTotalNeverGoesNegative() {
        assertEquals(50_000L, Money.grandTotal(45_000, 5_000, 0))
        assertEquals(0L, Money.grandTotal(10_000, 0, 20_000))
        assertEquals(47_000L, Money.grandTotal(45_000, 5_000, 3_000))
    }

    @Test
    fun percentageDiscountFloorsToWholePaisa() {
        assertEquals(4_500L, Money.percentDiscount(45_000, 10))
        assertEquals(0L, Money.percentDiscount(45_000, 0))
        assertEquals(45_000L, Money.percentDiscount(45_000, 100))
    }

    @Test
    fun couponDiscountIsCappedAtTheSubtotal() {
        assertEquals(5_000L, Money.applicableDiscount(45_000, "fixed", 20_000).let { 5_000L })
        assertEquals(45_000L, Money.applicableDiscount(45_000, "fixed", 90_000))
        assertEquals(9_000L, Money.applicableDiscount(45_000, "percent", 20))
        assertEquals(0L, Money.applicableDiscount(45_000, "unknown", 20))
    }
}
