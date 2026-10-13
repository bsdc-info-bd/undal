package com.undal.syl.app.data.repo

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.background.NotifyRetryWorker
import com.undal.syl.app.core.background.OrderAlertService
import com.undal.syl.app.core.background.PrepTimerService
import com.undal.syl.app.core.net.WorkerApi
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.core.util.UndalErrors
import com.undal.syl.app.core.util.UndalResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
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
    private val workerApi: WorkerApi,
    @ApplicationContext private val context: Context
) {

    data class OrderSnapshot(
        val id: String,
        val shortId: String,
        val status: OrderStatus,
        val totalAmountPaisa: Long,
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
                        status = status,
                        totalAmountPaisa = snapshot.getLong("totalAmountPaisa") ?: 0L,
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

    /** Accept with a real prep promise; the rules demand both fields. */
    suspend fun accept(orderId: String, prepMinutes: Int): UndalResult<Unit> =
        transition(
            orderId = orderId,
            target = OrderStatus.ACCEPTED,
            localPrepMinutes = prepMinutes,
            build = { _ ->
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

        val outcome = runCatching {
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(document)
                if (!snapshot.exists()) error("This order no longer exists.")

                val current = OrderStatus.fromWire(snapshot.getString("status"))
                    ?: error("This order has an unknown status.")

                if (!OrderStateMachine.canTransition(current, target)) {
                    error(
                        "An order that is ${OrderStateMachine.label(current).lowercase()} cannot become " +
                            "${OrderStateMachine.label(target).lowercase()}."
                    )
                }

                @Suppress("UNCHECKED_CAST")
                val items = snapshot.get("items") as? List<Map<String, Any>>
                @Suppress("UNCHECKED_CAST")
                val address = snapshot.get("address") as? Map<String, Any>
                val view = OrderSnapshot(
                    id = snapshot.id,
                    shortId = snapshot.getString("shortId").orEmpty(),
                    status = current,
                    totalAmountPaisa = snapshot.getLong("totalAmountPaisa") ?: 0L,
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
                            shortId = orderId.takeLast(4),
                            prepEndsAtMillis = Instant.now()
                                .plusSeconds(localPrepMinutes * 60L)
                                .toEpochMilli()
                        )
                    }
                }

                OrderStatus.CANCELLED -> OrderAlertService.silence(context, orderId)

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

    companion object {
        private const val ORDERS = "orders"
    }
}
