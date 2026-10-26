package com.undal.syl.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuDietaryFilterTest {

    @Test
    fun vegetarianFilterIncludesOnlyVerifiedDishesAndFullyVegetarianCombos() {
        val vegetarian = dish("vegetable-curry", veg = true)
        val meat = dish("chicken-curry", veg = false)
        val snapshot = MenuSnapshot(
            items = listOf(vegetarian, meat),
            combos = listOf(
                combo("veg-meal", listOf("vegetable-curry")),
                combo("mixed-meal", listOf("vegetable-curry", "chicken-curry")),
                combo("unknown-meal", listOf("missing-dish")),
                combo("empty-meal", emptyList())
            )
        )

        assertEquals(setOf("vegetable-curry", "veg-meal"), snapshot.vegetarianCardIds())
    }

    @Test
    fun popularQuickFilterUsesTheExistingRealOrderThreshold() {
        val snapshot = MenuSnapshot(
            items = listOf(
                dish("not-yet-popular", veg = false, orderCount = MenuItem.POPULAR_THRESHOLD - 1),
                dish("popular", veg = false, orderCount = MenuItem.POPULAR_THRESHOLD)
            )
        )

        assertEquals(setOf("popular"), snapshot.popularDishIds())
    }

    private fun dish(id: String, veg: Boolean, orderCount: Long = 0L) = MenuItem(
        id = id,
        name = id,
        description = "",
        amountPaisa = 10_000L,
        discountAmountPaisa = 0L,
        available = true,
        veg = veg,
        spiceLevel = 0,
        tags = emptyList(),
        orderCount = orderCount,
        categoryId = "main",
        subcategoryId = "",
        imageUrl = "",
        prepMinutes = 20
    )

    private fun combo(id: String, dishIds: List<String>) = Combo(
        id = id,
        name = id,
        description = "",
        amountPaisa = 10_000L,
        regularAmountPaisa = 10_000L,
        itemRefs = dishIds.map { Combo.ComboRef(menuItemId = it, qty = 1) },
        imageUrl = "",
        available = true
    )
}
