package com.undal.syl.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dish's price is the price it is sold at (C-029 · C-030 · V-050).
 *
 * There used to be a second kind of dish here: one seeded from a published menu
 * with a `priceFrom` flag, which the customer app drew as "Price needs
 * confirming", refused to add to a basket, and the kitchen had to clear by hand
 * before it could be sold. It was a footgun disguised as a safety feature — every
 * seeded dish arrived unsellable, the switch was named something an owner had to be
 * told about, and the storefront showed a menu nobody could order from. Every dish
 * the kitchen publishes is now a real price and is orderable, and "sold out" is the
 * only way to take it off sale.
 *
 * These tests are the guard on that: a price is honoured exactly, the discount is
 * derived from it, and the sold-out switch is the only thing that stops an order.
 */
class MenuPricingTest {

    @Test
    fun `a dish with a plain price is orderable at that price`() {
        val dish = MenuItem.fromDocument(
            "chicken-kabab",
            mapOf(
                "name" to "Chicken Kabab",
                "amountPaisa" to 4_500L,
                "available" to true,
                "categoryId" to "snacks"
            )
        )

        assertTrue("a published dish is on sale", dish.available)
        assertEquals(4_500L, dish.amountPaisa)
        assertEquals(4_500L, dish.payablePaisa)
        assertFalse("no discount means no strike-through", dish.hasDiscount)
    }

    @Test
    fun `a discount is what the customer pays, and the list price is what is struck through`() {
        val dish = MenuItem.fromDocument(
            "chicken-chaap",
            mapOf(
                "name" to "Chicken Chaap",
                "amountPaisa" to 16_500L,
                "discountAmountPaisa" to 3_300L,
                "available" to true
            )
        )

        assertEquals(13_200L, dish.payablePaisa)
        assertEquals(16_500L, dish.amountPaisa)
        assertTrue(dish.hasDiscount)
    }

    @Test
    fun `sold out is the only thing that takes a priced dish off sale`() {
        val dish = MenuItem.fromDocument(
            "plain-rice",
            mapOf(
                "name" to "Plain Rice",
                "amountPaisa" to 6_000L,
                "available" to false
            )
        )

        assertFalse(dish.available)
        // The price is still published, because "finished for today" is information
        // a customer wants: the dish stays on the page with a badge.
        assertEquals(6_000L, dish.payablePaisa)
    }

    @Test
    fun `a missing available flag is treated as not on sale, never as free`() {
        val dish = MenuItem.fromDocument("mystery", mapOf("name" to "Mystery box", "amountPaisa" to 1_000L))

        assertFalse(dish.available)
        assertEquals(1_000L, dish.payablePaisa)
    }

    @Test
    fun `a discount larger than the price cannot make a dish cheaper than nothing`() {
        val dish = MenuItem.fromDocument(
            "broken",
            mapOf("name" to "Broken row", "amountPaisa" to 1_000L, "discountAmountPaisa" to 5_000L, "available" to true)
        )

        // `hasDiscount` requires the discount to be smaller than the price, so a
        // malformed document falls back to the list price instead of a negative one.
        assertFalse(dish.hasDiscount)
        assertEquals(1_000L, dish.payablePaisa)
    }
}
