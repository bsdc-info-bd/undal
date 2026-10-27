package com.undal.syl.app.data.repo

import java.util.Locale
import android.content.Context
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.background.NotifyRetryWorker
import com.undal.syl.app.core.background.OrderAlertService
import com.undal.syl.app.core.background.PrepTimerService
import com.undal.syl.app.core.net.WorkerApi
import com.undal.syl.app.core.telemetry.UndalTelemetry
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.core.util.UndalErrors
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.core.util.onSuccess
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * The kitchen side of the order lifecycle (PART G4 · V-028 · V-029 · V-031 … V-033).
 *
 * Every transition is one Firestore transaction that:
 *   1. re-reads the order so the status cannot be moved from a stale copy,
 *   2. refuses the move unless the shared state machine allows it,
 *   3. appends exactly one timeline entry whose timestamp is a server timestamp —
 *      the security rules require the appended entry to carry `request.time`,
 *   4. writes only the fields the rules permit for that transition.
 *
 * After the write succeeds, the notification relay is queued and the local alert
 * state (ringing sound, prep countdown) is reconciled on this device. A failed
 * relay never rolls back an order: it is queued for retry instead.
 */
@Singleton
class OrderStatusRepo @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val database: FirebaseDatabase,
    private val workerApi: WorkerApi,
    private val telemetry: UndalTelemetry,
    @ApplicationContext private val context: Context
) {

    data class OrderSnapshot(
        val id: String,
        val shortId: String,
        /** Who placed it — the mirror's own read rule keys off this. */
        val customerId: String,
        val status: OrderStatus,
        val totalAmountPaisa: Long,
        val createdAt: Instant?,
        val prepEndsAt: Instant?,
        val customerName: String,
        val phone: String,
        val addressTitle: String,
        val addressDesc: String,
        val addressNote: String,
        val customerLat: Double,
        val customerLng: Double,
        val distanceKm: Double,
        val itemCount: Int
    )

    fun observeOrder(orderId: String): Flow<OrderSnapshot?> = callbackFlow {
        val registration = firestore.collection(ORDERS).document(orderId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }
                val status = OrderStatus.fromWire(snapshot.getString("status")) ?: return@addSnapshotListener
                @Suppress("UNCHECKED_CAST")
                val address = snapshot.get("address") as? Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val items = snapshot.get("items") as? List<Map<String, Any>>
                trySend(
                    OrderSnapshot(
                        id = snapshot.id,
                        shortId = snapshot.getString("shortId").orEmpty(),
                        customerId = snapshot.getString("customerId").orEmpty(),
                        status = status,
                        totalAmountPaisa = snapshot.getLong("totalAmountPaisa") ?: 0L,
                        createdAt = snapshot.getTimestamp("createdAt")?.toDate()?.toInstant(),
                        prepEndsAt = snapshot.getTimestamp("prepEndsAt")?.toDate()?.toInstant(),
                        customerName = snapshot.getString("customerName").orEmpty(),
                        phone = snapshot.getString("phone").orEmpty(),
                        addressTitle = address?.get("title") as? String ?: "",
                        addressDesc = address?.get("desc") as? String ?: "",
                        addressNote = address?.get("note") as? String ?: "",
                        customerLat = (address?.get("lat") as? Number)?.toDouble() ?: 0.0,
                        customerLng = (address?.get("lng") as? Number)?.toDouble() ?: 0.0,
                        distanceKm = (snapshot.getDouble("distanceKm") ?: 0.0),
                        itemCount = items?.size ?: 0
                    )
                )
            }
        awaitClose { registration.remove() }
    }

    /**
     * Accept with a real prep promise; the rules demand both fields.
     *
     * The moment it succeeds, the phone reports how long the order waited — the
     * one number that says whether this kitchen is coping (P-024).
     */
    suspend fun accept(orderId: String, prepMinutes: Int): UndalResult<Unit> {
        var placedAt: Instant? = null
        val outcome = transition(
            orderId = orderId,
            target = OrderStatus.ACCEPTED,
            localPrepMinutes = prepMinutes,
            build = { view ->
                placedAt = view.createdAt
                val prepEndsAt = com.google.firebase.Timestamp(
                    java.util.Date.from(Instant.now().plusSeconds(prepMinutes * 60L))
                )
                mapOf(
                    "status" to OrderStatus.ACCEPTED.wire,
                    "prepMinutes" to prepMinutes,
                    "prepEndsAt" to prepEndsAt,
                    "acceptedAt" to FieldValue.serverTimestamp()
                )
            }
        )
        return outcome.onSuccess {
            val placed = placedAt
            if (placed != null) {
                telemetry.orderAccepted(Duration.between(placed, Instant.now()).toMillis())
            }
        }
    }

    suspend fun reject(orderId: String, reason: String): UndalResult<Unit> =
        transition(
            orderId = orderId,
            target = OrderStatus.CANCELLED,
            build = { _ ->
                mapOf(
                    "status" to OrderStatus.CANCELLED.wire,
                    "cancelNote" to reason.trim().take(280)
                )
            }
        )

    /** "Is the order ready?" — the moment the kitchen confirms the food is packed. */
    suspend fun markReady(orderId: String): UndalResult<Unit> =
        transition(
            orderId = orderId,
            target = OrderStatus.READY,
            build = { _ ->
                mapOf(
                    "status" to OrderStatus.READY.wire,
                    "readyAt" to FieldValue.serverTimestamp()
                )
            }
        )

    suspend fun markInDelivery(orderId: String): UndalResult<Unit> =
        transition(
            orderId = orderId,
            target = OrderStatus.IN_DELIVERY,
            build = { _ ->
                mapOf(
                    "status" to OrderStatus.IN_DELIVERY.wire,
                    "inDeliveryAt" to FieldValue.serverTimestamp()
                )
            }
        )

    /** Delivered requires the cash to be counted and matched exactly. */
    suspend fun markDelivered(orderId: String, collectedAmountPaisa: Long): UndalResult<Unit> =
        transition(
            orderId = orderId,
            target = OrderStatus.DELIVERED,
            build = { _ ->
                mapOf(
                    "status" to OrderStatus.DELIVERED.wire,
                    "collectedAmountPaisa" to collectedAmountPaisa,
                    "deliveredAt" to FieldValue.serverTimestamp()
                )
            },
            validate = { order -> OrderStateMachine.isValidDelivered(collectedAmountPaisa, order.totalAmountPaisa) }
        )

    private suspend fun transition(
        orderId: String,
        target: OrderStatus,
        build: (OrderSnapshot) -> Map<String, Any>,
        validate: (OrderSnapshot) -> Boolean = { true },
        localPrepMinutes: Int = 0
    ): UndalResult<Unit> {
        val document = firestore.collection(ORDERS).document(orderId)
        // The reason written by this transition, kept for the live mirror (see
        // below): a cancellation's reason belongs on the customer's screen in the
        // same breath as the word "cancelled".
        var transitionalNote = ""
        // Who the order belongs to, read in the same transaction that moves it.
        var transitionalCustomerId = ""
        // The order's number as the customer knows it (UND-1042), for the notice.
        var transitionalShortId = ""

        val outcome = runCatching {
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(document)
                if (!snapshot.exists()) error("This order no longer exists.")

                val current = OrderStatus.fromWire(snapshot.getString("status"))
                    ?: error("This order has an unknown status.")

                if (!OrderStateMachine.canTransition(current, target)) {
                    error(
                        "An order that is ${OrderStateMachine.label(current).lowercase(Locale.ENGLISH)} cannot become " +
                            "${OrderStateMachine.label(target).lowercase(Locale.ENGLISH)}."
                    )
                }

                @Suppress("UNCHECKED_CAST")
                val items = snapshot.get("items") as? List<Map<String, Any>>
                @Suppress("UNCHECKED_CAST")
                val address = snapshot.get("address") as? Map<String, Any>
                val view = OrderSnapshot(
                    id = snapshot.id,
                    shortId = snapshot.getString("shortId").orEmpty(),
                    customerId = snapshot.getString("customerId").orEmpty(),
                    status = current,
                    totalAmountPaisa = snapshot.getLong("totalAmountPaisa") ?: 0L,
                    createdAt = snapshot.getTimestamp("createdAt")?.toDate()?.toInstant(),
                    prepEndsAt = snapshot.getTimestamp("prepEndsAt")?.toDate()?.toInstant(),
                    customerName = snapshot.getString("customerName").orEmpty(),
                    phone = snapshot.getString("phone").orEmpty(),
                    addressTitle = address?.get("title") as? String ?: "",
                    addressDesc = address?.get("desc") as? String ?: "",
                    addressNote = address?.get("note") as? String ?: "",
                    customerLat = (address?.get("lat") as? Number)?.toDouble() ?: 0.0,
                    customerLng = (address?.get("lng") as? Number)?.toDouble() ?: 0.0,
                    distanceKm = snapshot.getDouble("distanceKm") ?: 0.0,
                    itemCount = items?.size ?: 0
                )

                if (!validate(view)) {
                    error("That change is not allowed for this order yet.")
                }

                val update = build(view).toMutableMap()
                transitionalNote = (update["cancelNote"] as? String).orEmpty()
                transitionalCustomerId = view.customerId
                transitionalShortId = view.shortId
                // The rules require exactly one appended entry per transition,
                // carrying only the new status. Per-step times are the dedicated
                // server-stamped fields (acceptedAt, readyAt, inDeliveryAt,
                // deliveredAt) set by each transition above, because Firestore
                // cannot nest a server-timestamp transform inside an arrayUnion
                // element.
                update["timeline"] = FieldValue.arrayUnion(
                    mapOf("status" to target.wire)
                )
                update["updatedAt"] = FieldValue.serverTimestamp()
                transaction.update(document, update)
                null
            }.await()
            Unit
        }.fold(
            onSuccess = { UndalResult.Success(Unit) },
            onFailure = { UndalResult.Failure(UndalErrors.firestore(it), it) }
        )

        if (outcome is UndalResult.Success) {
            // The live mirror is written *here*, not only by the notification
            // relay, and it is written first-class rather than as a side effect of
            // a push succeeding: it is what every customer and kitchen screen
            // listens to, and a mirror that only moves when WorkManager gets a
            // 200 is a screen that does not move when the Worker is down, rate
            // limited, or simply slower than the order. A cancellation in
            // particular must be visible on the customer's tracking page while
            // they are still looking at it.
            mirrorStatus(orderId, target, transitionalNote, transitionalCustomerId)

            // Local reconciliation on this device: an accepted or cancelled order
            // must stop ringing immediately, and an accepted order starts the
            // sticky prep countdown with its ten-second tick (V-034).
            when (target) {
                OrderStatus.ACCEPTED -> {
                    OrderAlertService.silence(context, orderId)
                    if (localPrepMinutes > 0) {
                        PrepTimerService.start(
                            context = context,
                            orderId = orderId,
                            shortId = transitionalShortId.ifBlank { orderId.takeLast(4) },
                            prepEndsAtMillis = Instant.now()
                                .plusSeconds(localPrepMinutes * 60L)
                                .toEpochMilli()
                        )
                    }
                }

                OrderStatus.CANCELLED -> {
                    OrderAlertService.silence(context, orderId)
                    // A cancelled order has nothing left to cook, so the prep
                    // countdown — and its ten-second tick — ends with it. It used
                    // to be left running: the kitchen cancelled an order, the
                    // sticky countdown kept counting down to a promise nobody had
                    // to keep, and the phone ticked every ten seconds until the
                    // process was killed.
                    PrepTimerService.stop(context)
                }

                OrderStatus.READY, OrderStatus.IN_DELIVERY, OrderStatus.DELIVERED ->
                    PrepTimerService.stop(context)

                else -> Unit
            }

            // The relay is queued through WorkManager, so a dead spot in the
            // network delays the customer's push instead of losing it.
            NotifyRetryWorker.enqueue(
                context = context,
                orderId = orderId,
                kind = NotifyRetryWorker.KIND_ORDER_STATUS,
                status = target.wire
            )
        }
        return outcome
    }

    /**
     * The live mirror write for one transition.
     *
     * Fire-and-forget on purpose: the Firebase SDK queues it in memory and persists
     * it until the device is online again, so an order accepted in a lift with no
     * signal still reaches the customer's screen a minute later. Nothing about an
     * order transition is rolled back because this failed — Firestore is still the
     * authority, and the Worker's relay writes the same node.
     */
    private fun mirrorStatus(orderId: String, status: OrderStatus, note: String, customerId: String) {
        runCatching {
            val payload = mutableMapOf<String, Any>(
                "status" to status.wire,
                "updatedAt" to ServerValue.TIMESTAMP
            )
            // The customer's own read rule is `customerId === auth.uid`, and a
            // mirror first written by a phone that did not have the field would be
            // readable by the kitchen and invisible to the person waiting for the
            // food. It is written on every transition, so the field cannot go
            // missing.
            if (customerId.isNotBlank()) payload["customerId"] = customerId
            if (note.isNotBlank()) payload["vendorNote"] = note.take(300)
            database.getReference(LIVE).child(orderId).updateChildren(payload)
        }
    }


    companion object {
        private const val ORDERS = "orders"
        private const val LIVE = "orderLive"
    }
}
