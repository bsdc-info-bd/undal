package com.undal.syl.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The menu-health card on the vendor menu manager (V-057).
 *
 * The card answers one question — "is there anything to fix before service?" — and
 * these tests are the definition of every word in it. A sold-out dish is not a
 * fault of the menu, a dish with no price is worse than one with no photo, and a
 * menu that is entirely sold out is the worst state of all because the shop looks
 * open and has nothing in it.
 */
class MenuAuditTest {

    private fun dish(
        id: String,
        price: Long = 12_000L,
        available: Boolean = true,
        image: String = "https://example.test/$id.webp",
        prep: Int = 20,
        featured: Boolean = false
    ) = MenuItem(
        id = id,
        name = id.replace('-', ' '),
        description = "",
        amountPaisa = price,
        discountAmountPaisa = 0L,
        available = available,
        veg = false,
        spiceLevel = 1,
        tags = emptyList(),
        orderCount = 0L,
        categoryId = "bhorta",
        subcategoryId = "",
        imageUrl = image,
        prepMinutes = prep,
        featured = featured
    )

    @Test
    fun `a complete menu is healthy`() {
        val health = MenuAudit.of(listOf(dish("aloo-bhorta"), dish("daal", featured = true)))

        assertEquals(2, health.dishes)
        assertEquals(2, health.onSale)
        assertEquals(0, health.soldOut)
        assertEquals(1, health.unfeatured)
        assertTrue(health.isHealthy)
        assertTrue(health.problems.isEmpty())
    }

    @Test
    fun `a sold-out dish is a fact, not a fault`() {
        val health = MenuAudit.of(listOf(dish("aloo-bhorta"), dish("chicken", available = false)))

        assertEquals(1, health.onSale)
        assertEquals(1, health.soldOut)
        assertTrue("a kitchen that ran out of chicken is still ready to cook", health.isHealthy)
    }

    @Test
    fun `a menu that is entirely sold out is not healthy`() {
        val health = MenuAudit.of(listOf(dish("aloo-bhorta", available = false)))

        assertFalse(health.isHealthy)
        assertEquals(1, health.problems.size)
        assertTrue(health.problems.first().contains("sold out"))
    }

    @Test
    fun `an empty menu is not healthy`() {
        val health = MenuAudit.of(emptyList())

        assertFalse(health.isHealthy)
        assertEquals(1, health.problems.size)
    }

    @Test
    fun `a dish with no price and no photo and no prep time is counted three times`() {
        val health = MenuAudit.of(
            listOf(dish("ghost", price = 0L, image = "", prep = 0))
        )

        assertEquals(1, health.unpriced)
        assertEquals(1, health.missingPhoto)
        assertEquals(1, health.missingPrep)
        assertFalse(health.isHealthy)
        // The price comes first: it is the one that cannot be fixed by taking a photo.
        assertTrue(health.problems.first().contains("no price"))
        assertEquals(3, health.problems.size)
    }

    @Test
    fun `a discount does not read as a missing price`() {
        val discounted = dish("kabab", price = 20_000L).copy(discountAmountPaisa = 5_000L)
        val health = MenuAudit.of(listOf(discounted))

        assertEquals(0, health.unpriced)
        assertTrue(health.isHealthy)
    }

    @Test
    fun `the problems are counted for one dish as one dish`() {
        val health = MenuAudit.of(listOf(dish("solo", image = "")))

        assertEquals(1, health.problems.size)
        assertTrue(health.problems.first().startsWith("1 dish has"))
    }
}
