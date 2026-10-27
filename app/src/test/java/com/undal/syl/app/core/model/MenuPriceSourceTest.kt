package com.undal.syl.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPriceSourceTest {
    @Test
    fun startingPriceFlagSurvivesFirestoreAndOldMenuDocumentsDefaultToFixedPrice() {
        val sourcePriced = MenuItem.fromDocument(
            "chicken-kabab",
            mapOf("name" to "Chicken Kabab", "priceFrom" to true, "available" to true)
        )
        val fixedPrice = MenuItem.fromDocument(
            "rice",
            mapOf("name" to "Plain Rice", "available" to true)
        )

        assertTrue(sourcePriced.priceFrom)
        assertFalse(sourcePriced.available)
        assertFalse(MenuCard.Dish(sourcePriced).available)
        assertEquals(0, sourcePriced.prepMinutes)
        assertFalse(fixedPrice.priceFrom)
        assertTrue(fixedPrice.available)
    }
}
