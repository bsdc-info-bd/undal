package com.undal.syl.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Validation messages are user-facing copy, so they are pinned here: a vague
 * error is a bug in this product.
 */
class ValidatorsTest {

    @Test
    fun namesMustBeRealEnough() {
        assertNull(Validators.name("Rahim Chowdhury"))
        assertNotNull(Validators.name(""))
        assertNotNull(Validators.name("Ra"))
        assertNotNull(Validators.name("12345"))
        assertNotNull(Validators.name("x".repeat(120)))
    }

    @Test
    fun emailsMustBeWellFormed() {
        assertNull(Validators.email("kitchen@undal.local"))
        assertNull(Validators.email("orders+undal@gmail.com"))
        assertNotNull(Validators.email(""))
        assertNotNull(Validators.email("kitchen@undal"))
        assertNotNull(Validators.email("kitchen.undal.local"))
    }

    @Test
    fun bangladeshiMobilesFollowTheRealNumberPlan() {
        assertNull(Validators.bdMobile("01712345678"))
        assertNull(Validators.bdMobile("01312345678"))
        assertNull(Validators.bdMobile("01912345678"))
        assertNotNull(Validators.bdMobile("01212345678"))
        assertNotNull(Validators.bdMobile("0171234567"))
        assertNotNull(Validators.bdMobile(""))
    }

    @Test
    fun mobileNumbersNormaliseToTheLocalElevenDigits() {
        // Every shape a customer might paste, landing on 01XXXXXXXXX.
        assertEquals("01712345678", Validators.normalizeBdMobile("+880 1712-345678"))
        assertEquals("01712345678", Validators.normalizeBdMobile("8801712345678"))
        assertEquals("01712345678", Validators.normalizeBdMobile("01712 345678"))
        assertNull(Validators.normalizeBdMobile("0171234567"))
        assertNull(Validators.normalizeBdMobile("++880 9999"))
    }

    @Test
    fun passwordsNeedLengthAndVariety() {
        assertNull(Validators.password("undal123"))
        assertNotNull(Validators.password(""))
        assertNotNull(Validators.password("12345"))
        assertNotNull(Validators.password("123456"))
        assertEquals("The two passwords do not match.", Validators.passwordConfirmation("undal123", "undal124"))
        assertNull(Validators.passwordConfirmation("undal123", "undal123"))
    }

    @Test
    fun strengthMeterCategoriesAreOrdered() {
        assertEquals(0, Validators.assessPassword("").score)
        assertEquals(1, Validators.assessPassword("abcd").score)
        assertEquals(2, Validators.assessPassword("abcd1234").score)
        assertEquals(3, Validators.assessPassword("abcd123456").score)
        assertEquals(4, Validators.assessPassword("abcd1234!@#$").score)
        assertEquals("Very strong", Validators.assessPassword("abcd1234!@#$").label)
    }

    @Test
    fun addressFieldsAreAllMandatory() {
        assertNull(Validators.addressTitle("Home"))
        assertNull(Validators.addressDescription("Flat 4B, Bandar Bazar"))
        assertNull(Validators.deliveryNote("Call when you reach the gate"))
        assertNotNull(Validators.addressTitle(""))
        assertNotNull(Validators.addressDescription("  "))
        assertNotNull(Validators.deliveryNote(""))
    }

    @Test
    fun couponCodesAreNormalisedAndBounded() {
        assertNull(Validators.couponCode("undal50"))
        assertNull(Validators.couponCode("EID-2026"))
        assertNotNull(Validators.couponCode(""))
        assertNotNull(Validators.couponCode("no spaces here"))
        assertNotNull(Validators.couponCode("x"))
    }

    @Test
    fun spiceLevelsUseTheContractRange() {
        assertNull(Validators.rating(1))
        assertNull(Validators.rating(3))
        assertNotNull(Validators.rating(0))
        assertNotNull(Validators.rating(4))
    }
}
