package com.undal.syl.app.core.util

/**
 * The order lifecycle, enforced in three places that must always agree:
 *   · here (client UI: no impossible button is ever enabled)
 *   · firestore.rules (the authority: an illegal write is rejected)
 *   · the Worker notification relay (only real transitions are announced)
 *
 * Forward-only. `delivered` and `cancelled` are terminal.
 */
enum class OrderStatus(val wire: String) {
    PLACED("placed"),
    ACCEPTED("accepted"),
    READY("ready"),
    IN_DELIVERY("in_delivery"),
    DELIVERED("delivered"),
    CANCELLED("cancelled");

    companion object {
        fun fromWire(value: String?): OrderStatus? = entries.firstOrNull { it.wire == value }
    }
}

object OrderStateMachine {

    /** Allowed vendor moves. Customers have no moves at all (C-077). */
    private val transitions: Map<OrderStatus, Set<OrderStatus>> = mapOf(
        OrderStatus.PLACED to setOf(OrderStatus.ACCEPTED, OrderStatus.CANCELLED),
        OrderStatus.ACCEPTED to setOf(OrderStatus.READY, OrderStatus.CANCELLED),
        OrderStatus.READY to setOf(OrderStatus.IN_DELIVERY),
        OrderStatus.IN_DELIVERY to setOf(OrderStatus.DELIVERED),
        OrderStatus.DELIVERED to emptySet(),
        OrderStatus.CANCELLED to emptySet()
    )

    fun allowedNext(from: OrderStatus): Set<OrderStatus> = transitions[from] ?: emptySet()

    fun canTransition(from: OrderStatus, to: OrderStatus): Boolean = allowedNext(from).contains(to)

    fun isTerminal(status: OrderStatus): Boolean = status == OrderStatus.DELIVERED || status == OrderStatus.CANCELLED

    fun isActive(status: OrderStatus): Boolean =
        status == OrderStatus.PLACED || status == OrderStatus.ACCEPTED ||
            status == OrderStatus.READY || status == OrderStatus.IN_DELIVERY

    /**
     * The status the customer should see, from the two places it is written.
     *
     * Firestore carries the order document; the Realtime Database mirror carries
     * the same status a beat earlier. The screen must never go **backwards** — a
     * stale mirror still saying "accepted" after the kitchen cancelled is not an
     * older truth, it is a wrong one, and the customer's page froze on it while the
     * history list (which reads only Firestore) already said "cancelled".
     *
     * So the rule is: a cancellation anywhere wins immediately (it is terminal, and
     * there is exactly one way to reach it), and otherwise the status further along
     * the timeline wins. Nothing can move this backwards, which means the screen
     * that a customer leaves open cannot disagree with the list they open next.
     */
    fun newest(firestoreStatus: OrderStatus, liveStatus: OrderStatus?): OrderStatus {
        if (liveStatus == null) return firestoreStatus
        if (firestoreStatus == OrderStatus.CANCELLED || liveStatus == OrderStatus.CANCELLED) {
            return OrderStatus.CANCELLED
        }
        val firestoreStep = timeline.indexOf(firestoreStatus)
        val liveStep = timeline.indexOf(liveStatus)
        return if (liveStep > firestoreStep) liveStatus else firestoreStatus
    }

    /** Ordered step list used by the customer timeline (cancelled is shown apart). */
    val timeline: List<OrderStatus> = listOf(
        OrderStatus.PLACED,
        OrderStatus.ACCEPTED,
        OrderStatus.READY,
        OrderStatus.IN_DELIVERY,
        OrderStatus.DELIVERED
    )

    fun stepIndex(status: OrderStatus): Int = timeline.indexOf(status)

    /** Human labels used in the customer app and the kitchen queue. */
    fun label(status: OrderStatus): String = when (status) {
        OrderStatus.PLACED -> "Placed"
        OrderStatus.ACCEPTED -> "Cooking"
        OrderStatus.READY -> "Ready"
        OrderStatus.IN_DELIVERY -> "On the way"
        OrderStatus.DELIVERED -> "Delivered"
        OrderStatus.CANCELLED -> "Cancelled"
    }

    /**
     * The single rule that stops an impossible accept: the kitchen must publish
     * a positive prep time and a future finish time, exactly as the rules require.
     */
    fun isValidAccept(prepMinutes: Int?, prepEndsAt: java.time.Instant?, now: java.time.Instant = java.time.Instant.now()): Boolean =
        prepMinutes != null && prepMinutes in 1..180 && prepEndsAt != null && prepEndsAt.isAfter(now)

    fun isValidCancel(cancelNote: String?): Boolean = !cancelNote.isNullOrBlank() && cancelNote.trim().length >= 3

    /**
     * Delivered requires the cash to have been counted. The money is compared in
     * paisa so the kitchen cannot record a rounded-up collection by accident.
     */
    fun isValidDelivered(collectedAmountPaisa: Long?, totalAmountPaisa: Long): Boolean =
        collectedAmountPaisa != null && collectedAmountPaisa == totalAmountPaisa

    /** Prep-time chips offered on the kitchen accept sheet (V-028). */
    val prepMinuteOptions: List<Int> = listOf(10, 15, 20, 25, 30, 45)

    /** Reject templates shown on the reject sheet (V-029); a note is still required. */
    val rejectTemplates: List<String> = listOf(
        "Some items are finished for today",
        "The kitchen has closed for now",
        "We cannot reach your address",
        "Too far from our delivery area"
    )
}
