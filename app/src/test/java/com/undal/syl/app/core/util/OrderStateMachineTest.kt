package com.undal.syl.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The order state machine is mirrored in firestore.rules. If this test and the
 * rules ever disagree, the kitchen would see buttons that the database rejects,
 * so every transition is pinned here.
 */
class OrderStateMachineTest {

    @Test
    fun forwardPathIsCompleteAndOrdered() {
        assertTrue(OrderStateMachine.canTransition(OrderStatus.PLACED, OrderStatus.ACCEPTED))
        assertTrue(OrderStateMachine.canTransition(OrderStatus.ACCEPTED, OrderStatus.READY))
        assertTrue(OrderStateMachine.canTransition(OrderStatus.READY, OrderStatus.IN_DELIVERY))
        assertTrue(OrderStateMachine.canTransition(OrderStatus.IN_DELIVERY, OrderStatus.DELIVERED))
    }

    @Test
    fun cancellationIsOnlyAllowedBeforeCookingFinishes() {
        assertTrue(OrderStateMachine.canTransition(OrderStatus.PLACED, OrderStatus.CANCELLED))
        assertTrue(OrderStateMachine.canTransition(OrderStatus.ACCEPTED, OrderStatus.CANCELLED))
        assertFalse(OrderStateMachine.canTransition(OrderStatus.READY, OrderStatus.CANCELLED))
        assertFalse(OrderStateMachine.canTransition(OrderStatus.IN_DELIVERY, OrderStatus.CANCELLED))
        assertFalse(OrderStateMachine.canTransition(OrderStatus.DELIVERED, OrderStatus.CANCELLED))
    }

    @Test
    fun noBackwardsTransitionsEver() {
        val statuses = OrderStatus.entries
        statuses.forEach { to ->
            statuses.forEach { from ->
                if (OrderStateMachine.stepIndex(from) > OrderStateMachine.stepIndex(to) && to != OrderStatus.CANCELLED) {
                    assertFalse(
                        "must not move $from -> $to",
                        OrderStateMachine.canTransition(from, to)
                    )
                }
            }
        }
    }

    @Test
    fun skippingAheadIsImpossible() {
        assertFalse(OrderStateMachine.canTransition(OrderStatus.PLACED, OrderStatus.READY))
        assertFalse(OrderStateMachine.canTransition(OrderStatus.PLACED, OrderStatus.IN_DELIVERY))
        assertFalse(OrderStateMachine.canTransition(OrderStatus.PLACED, OrderStatus.DELIVERED))
        assertFalse(OrderStateMachine.canTransition(OrderStatus.ACCEPTED, OrderStatus.IN_DELIVERY))
        assertFalse(OrderStateMachine.canTransition(OrderStatus.ACCEPTED, OrderStatus.DELIVERED))
    }

    @Test
    fun terminalStatesAreSealed() {
        assertTrue(OrderStateMachine.isTerminal(OrderStatus.DELIVERED))
        assertTrue(OrderStateMachine.isTerminal(OrderStatus.CANCELLED))
        assertTrue(OrderStateMachine.allowedNext(OrderStatus.DELIVERED).isEmpty())
        assertTrue(OrderStateMachine.allowedNext(OrderStatus.CANCELLED).isEmpty())
    }

    @Test
    fun activeStatesDriveTheKitchenQueue() {
        assertTrue(OrderStateMachine.isActive(OrderStatus.PLACED))
        assertTrue(OrderStateMachine.isActive(OrderStatus.ACCEPTED))
        assertTrue(OrderStateMachine.isActive(OrderStatus.READY))
        assertTrue(OrderStateMachine.isActive(OrderStatus.IN_DELIVERY))
        assertFalse(OrderStateMachine.isActive(OrderStatus.DELIVERED))
        assertFalse(OrderStateMachine.isActive(OrderStatus.CANCELLED))
    }

    @Test
    fun acceptRequiresARealPrepPromise() {
        val now = Instant.parse("2026-10-13T12:00:00Z")
        assertTrue(OrderStateMachine.isValidAccept(20, now.plusSeconds(1200), now))
        assertFalse("no prep minutes", OrderStateMachine.isValidAccept(null, now.plusSeconds(1200), now))
        assertFalse("zero minutes", OrderStateMachine.isValidAccept(0, now.plusSeconds(1200), now))
        assertFalse("absurd minutes", OrderStateMachine.isValidAccept(400, now.plusSeconds(1200), now))
        assertFalse("finish time in the past", OrderStateMachine.isValidAccept(20, now.minusSeconds(60), now))
        assertFalse("no finish time", OrderStateMachine.isValidAccept(20, null, now))
    }

    @Test
    fun cancelRequiresAReadableNote() {
        assertTrue(OrderStateMachine.isValidCancel("The fish is finished for today"))
        assertFalse(OrderStateMachine.isValidCancel(null))
        assertFalse(OrderStateMachine.isValidCancel("   "))
        assertFalse(OrderStateMachine.isValidCancel("no"))
    }

    @Test
    fun deliveredRequiresTheExactCashTotal() {
        assertTrue(OrderStateMachine.isValidDelivered(58_500, 58_500))
        assertFalse(OrderStateMachine.isValidDelivered(58_000, 58_500))
        assertFalse(OrderStateMachine.isValidDelivered(null, 58_500))
    }

    @Test
    fun wireValuesMatchTheFirestoreContract() {
        assertEquals("placed", OrderStatus.PLACED.wire)
        assertEquals("accepted", OrderStatus.ACCEPTED.wire)
        assertEquals("ready", OrderStatus.READY.wire)
        assertEquals("in_delivery", OrderStatus.IN_DELIVERY.wire)
        assertEquals("delivered", OrderStatus.DELIVERED.wire)
        assertEquals("cancelled", OrderStatus.CANCELLED.wire)
        assertEquals(OrderStatus.IN_DELIVERY, OrderStatus.fromWire("in_delivery"))
        assertEquals(null, OrderStatus.fromWire("cooking"))
    }

    /**
     * The customer's tracking screen reads two copies of the status (the order
     * document and the live mirror) and must show the newer one. The bug this pins
     * down: the mirror still said "accepted" while the kitchen had already
     * cancelled, so the open tracking page kept showing a cooking order while the
     * history list on the next tab said "cancelled".
     */
    @Test
    fun theTrackingScreenNeverShowsAnOlderStatus() {
        // The mirror has not caught up: the document is the truth.
        assertEquals(
            OrderStatus.READY,
            OrderStateMachine.newest(OrderStatus.READY, OrderStatus.ACCEPTED)
        )
        // The mirror is ahead: the customer sees the tap, not the commit.
        assertEquals(
            OrderStatus.IN_DELIVERY,
            OrderStateMachine.newest(OrderStatus.READY, OrderStatus.IN_DELIVERY)
        )
        // A cancellation anywhere is final, whichever writer got there first.
        assertEquals(
            OrderStatus.CANCELLED,
            OrderStateMachine.newest(OrderStatus.ACCEPTED, OrderStatus.CANCELLED)
        )
        assertEquals(
            OrderStatus.CANCELLED,
            OrderStateMachine.newest(OrderStatus.CANCELLED, OrderStatus.ACCEPTED)
        )
        // No mirror at all is the ordinary case on a cold start.
        assertEquals(
            OrderStatus.PLACED,
            OrderStateMachine.newest(OrderStatus.PLACED, null)
        )
    }

    @Test
    fun stepIndexDrivesTheCustomerTimeline() {
        assertEquals(0, OrderStateMachine.stepIndex(OrderStatus.PLACED))
        assertEquals(4, OrderStateMachine.stepIndex(OrderStatus.DELIVERED))
        assertEquals(-1, OrderStateMachine.stepIndex(OrderStatus.CANCELLED))
    }
}
