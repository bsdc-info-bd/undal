package com.undal.syl.app.features.order

import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.data.repo.OrderRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderHistoryStateTest {
    private fun order(id: String, active: Boolean = false) = OrderRepo.OrderSummary(
        id = id, shortId = id, status = if (active) OrderStatus.ACCEPTED else OrderStatus.DELIVERED,
        totalAmountPaisa = 25000L, itemCount = 1, firstItemName = "Sample dish",
        createdAt = null, prepEndsAt = null, cancelNote = "", isActive = active
    )

    @Test fun olderActiveOrderIsPrioritizedBeforePaging() {
        val past = (1..24).map { order("past-$it") }
        val state = OrderHistoryViewModel.State(orders = past + order("cooking", active = true))
        assertEquals(listOf("cooking"), state.active.map { it.id })
        assertEquals(19, state.past.size)
        assertTrue(state.canLoadMore)
    }

    @Test fun prioritizingActiveOrdersPreservesOrderWithinEachSection() {
        val state = OrderHistoryViewModel.State(orders = listOf(
            order("recent-past"), order("recent-active", true),
            order("older-past"), order("older-active", true)
        ))
        assertEquals(listOf("recent-active", "older-active"), state.active.map { it.id })
        assertEquals(listOf("recent-past", "older-past"), state.past.map { it.id })
        assertFalse(state.canLoadMore)
    }

    @Test fun expandedPageRevealsRemainingPastOrdersWithoutDuplicates() {
        val orders = (1..30).map { order("past-$it") } + order("active", true)
        val first = OrderHistoryViewModel.State(orders = orders)
        val expanded = first.copy(visible = 40)
        assertEquals(20, first.shown.size)
        assertEquals(orders.size, expanded.shown.map { it.id }.distinct().size)
        assertEquals(30, expanded.past.size)
        assertFalse(expanded.canLoadMore)
    }

    @Test fun emptyHistoryHasNoSectionsOrMoreAction() {
        val state = OrderHistoryViewModel.State(loading = false)
        assertFalse(state.hasAnything)
        assertFalse(state.canLoadMore)
        assertTrue(state.active.isEmpty())
        assertTrue(state.past.isEmpty())
    }
}
