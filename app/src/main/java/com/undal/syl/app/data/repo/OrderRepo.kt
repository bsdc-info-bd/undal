package com.undal.syl.app.data.repo

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.undal.syl.app.core.background.NotifyRetryWorker
import com.undal.syl.app.core.cart.CartLine
import com.undal.syl.app.core.maps.UndalLocation
import com.undal.syl.app.core.model.MenuSnapshot
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalErrors
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.core.util.Validators
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Placing and reading orders (C-061 · C-069 · C-074 · C-075 · C-076).
 *
 * Placing an order is the one write in this app that moves money, so it is a
 * single Firestore transaction that does all four things the security rules
 * expect at once:
 *   1. bumps `counters/orders` and derives the UND-#### number from the new value,
 *   2. re-reads every dish at its live price and refuses to place an order whose
 *      price or availability changed since it was added to the cart,
 *   3. writes the order document exactly as the rules validate it,
 *   4. increments the coupon's `usedCount` by one — the only coupon write a
 *      customer is ever allowed to make.
 *
 * Only after that commit do the RTDB live mirror and the notification relay run,
 * because a live flag without an order behind it would be a lie, and a push about
 * an order that does not exist would ring the kitchen for nothing.
 */
@Singleton
class OrderRepo @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val database: FirebaseDatabase,
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context
) {

    /** Everything needed to place one order. */
    data class OrderDraft(
        val store: StoreConfig,
        val address: UndalLocation,
        val distanceKm: Double,
        val customerName: String,
        val phone: String,
        val lines: List<CartLine>,
        val couponCode: String,
        val note: String
    )

    data class PlacedOrder(val orderId: String, val shortId: String, val totalAmountPaisa: Long)

    /** A row in the order history. */
    data class OrderSummary(
        val id: String,
        val shortId: String,
        val status: OrderStatus,
        val totalAmountPaisa: Long,
        val itemCount: Int,
        val firstItemName: String,
        val createdAt: Instant?,
        val prepEndsAt: Instant?,
        val cancelNote: String,
        val isActive: Boolean
    )

    /** The receipt-style detail view. */
    data class OrderDetail(
        val id: String,
        val shortId: String,
        val status: OrderStatus,
        val liveStatus: String?,
        val customerName: String,
        val phone: String,
        val addressTitle: String,
        val addressDesc: String,
        val addressNote: String,
        val addressLat: Double,
        val addressLng: Double,
        val distanceKm: Double,
        val items: List<OrderItemLine>,
        val subtotalAmountPaisa: Long,
        val deliveryAmountPaisa: Long,
        val discountAmountPaisa: Long,
        val totalAmountPaisa: Long,
        val couponCode: String,
        val note: String,
        val statusNotes: List<String>,
        val timeline: List<Pair<OrderStatus, String>>,
        val prepMinutes: Int?,
        val prepEndsAt: Instant?,
        val cancelNote: String,
        val collectedAmountPaisa: Long?,
        val createdAt: Instant?
    ) {

        /** The kitchen's own words for this order, in the order they were written. */
        val kitchenNotes: List<String> get() = statusNotes.filter { it.isNotBlank() }
    }

    data class OrderItemLine(
        val name: String,
        val quantity: Int,
        val unitAmountPaisa: Long,
        val lineTotalPaisa: Long,
        val note: String
    )

    /** Raised inside the placement transaction with copy the customer should read. */
    private class OrderBlocked(message: String) : IllegalStateException(message)

    // ------------------------------------------------------------------ write
    suspend fun placeOrder(draft: OrderDraft): UndalResult<PlacedOrder> {
        val uid = auth.currentUser?.uid
            ?: return UndalResult.Failure("Please sign in again — your session expired.")
        if (draft.lines.isEmpty()) return UndalResult.Failure("Your cart is empty.")
        if (draft.lines.size > 40) return UndalResult.Failure("An order can hold at most 40 different dishes.")
        if (!draft.address.isValid) {
            return UndalResult.Failure("Your delivery address is incomplete — please re-pin it.")
        }
        val phone = Validators.normalizeBdMobile(draft.phone)
            ?: return UndalResult.Failure(
                "Add a valid Bangladeshi mobile number (01XXXXXXXXX) in your profile — " +
                    "the rider calls it when the food arrives."
            )
        if (draft.distanceKm < 0.0 || draft.distanceKm > 30.0) {
            return UndalResult.Failure("That address is outside the 30 km delivery limit.")
        }

        val orderReference = firestore.collection(ORDERS).document()
        val orderId = orderReference.id
        val couponCode = draft.couponCode.trim().uppercase()

        val outcome = runCatching {
            firestore.runTransaction<PlacedOrder> { transaction ->
                val counterReference = firestore.collection(COUNTERS).document(ORDERS)
                val counterSnapshot = transaction.get(counterReference)
                val nextCounterValue = (counterSnapshot.getLong("value") ?: 1000L) + 1L
                if (nextCounterValue > 999_999L) {
                    throw OrderBlocked("The order counter has run out — please tell the kitchen.")
                }
                val shortId = "UND-$nextCounterValue"

                // Live re-read of every dish: price and availability are the
                // kitchen's to change, and the customer must never be charged a
                // price they did not see.
                val itemLines = draft.lines.map { line ->
                    val collection = if (line.isCombo) COMBOS else MENU_ITEMS
                    val snapshot = transaction.get(firestore.collection(collection).document(line.id))
                    if (!snapshot.exists()) {
                        throw OrderBlocked("${line.name} is no longer on the menu. Please remove it and try again.")
                    }
                    val available = when {
                        line.isCombo -> snapshot.getBoolean("available") ?: true
                        else -> snapshot.getBoolean("available") ?: false
                    }
                    if (!available) {
                        throw OrderBlocked("${line.name} is finished for today. Please remove it to continue.")
                    }
                    val liveUnit = when {
                        line.isCombo -> snapshot.getLong("amountPaisa") ?: line.unitAmountPaisa
                        else -> {
                            val full = snapshot.getLong("amountPaisa") ?: line.unitAmountPaisa
                            val discount = snapshot.getLong("discountAmountPaisa") ?: 0L
                            if (discount in 1 until full) full - discount else full
                        }
                    }
                    if (liveUnit != line.unitAmountPaisa) {
                        throw OrderBlocked(
                            "${line.name} now costs ${Money.format(liveUnit)} (you had " +
                                "${Money.format(line.unitAmountPaisa)}). Please review your cart before ordering."
                        )
                    }
                    mapOf(
                        "id" to line.id,
                        "name" to line.name,
                        "qty" to line.quantity,
                        "unitAmountPaisa" to liveUnit,
                        "lineTotalAmountPaisa" to Money.lineTotal(liveUnit, line.quantity),
                        "note" to line.note,
                        "isCombo" to line.isCombo
                    )
                }

                val subtotal = itemLines.sumOf { it["lineTotalAmountPaisa"] as Long }
                val delivery = Money.deliveryFeePaisa(draft.distanceKm, draft.store.deliveryPerKmAmountPaisa)

                var discount = 0L
                var appliedCode = ""
                if (couponCode.isNotBlank()) {
                    val couponReference = firestore.collection(COUPONS).document(couponCode)
                    val couponSnapshot = transaction.get(couponReference)
                    if (!couponSnapshot.exists()) {
                        throw OrderBlocked("Coupon $couponCode no longer exists. Remove it to continue.")
                    }
                    val active = couponSnapshot.getBoolean("active") ?: false
                    val type = couponSnapshot.getString("type").orEmpty()
                    val value = couponSnapshot.getLong("value") ?: 0L
                    val usageLimit = couponSnapshot.getLong("usageLimit") ?: 0L
                    val usedCount = couponSnapshot.getLong("usedCount") ?: 0L
                    val minimum = couponSnapshot.getLong("minSubtotalAmountPaisa") ?: 0L
                    val expiry = couponSnapshot.getTimestamp("expiresAt")

                    if (!active) throw OrderBlocked("Coupon $couponCode was switched off. Remove it to continue.")
                    if (expiry != null && expiry.toDate().time <= System.currentTimeMillis()) {
                        throw OrderBlocked("Coupon $couponCode expired. Remove it to continue.")
                    }
                    if (usageLimit in 1..usedCount) {
                        throw OrderBlocked("Coupon $couponCode has just been used its last time. Sorry about that.")
                    }
                    if (subtotal < minimum) {
                        throw OrderBlocked(
                            "Coupon $couponCode needs ${Money.format(minimum)} of food. Remove it or add more."
                        )
                    }
                    discount = Money.applicableDiscount(subtotal, type, value)
                    if (discount <= 0L) throw OrderBlocked("Coupon $couponCode does not change this total.")
                    appliedCode = couponCode
                    transaction.update(couponReference, "usedCount", usedCount + 1L)
                }

                val total = Money.grandTotal(subtotal, delivery, discount)
                if (total <= 0L) throw OrderBlocked("This order would total nothing. Please review your cart.")

                val orderDocument = mutableMapOf<String, Any?>(
                    "shortId" to shortId,
                    "customerId" to uid,
                    "customerName" to draft.customerName.trim(),
                    "phone" to phone,
                    "items" to itemLines,
                    "subtotalAmountPaisa" to subtotal,
                    "deliveryAmountPaisa" to delivery,
                    "discountAmountPaisa" to discount,
                    "totalAmountPaisa" to total,
                    "payment" to "COD",
                    "address" to draft.address.toFirestoreMap(),
                    "distanceKm" to draft.distanceKm,
                    "status" to OrderStatus.PLACED.wire,
                    "timeline" to listOf(mapOf("status" to OrderStatus.PLACED.wire)),
                    "chatId" to orderId,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (appliedCode.isNotBlank()) orderDocument["couponCode"] = appliedCode
                if (draft.note.isNotBlank()) orderDocument["note"] = draft.note.trim().take(300)

                if (counterSnapshot.exists()) {
                    transaction.update(counterReference, "value", nextCounterValue)
                } else {
                    transaction.set(counterReference, mapOf("value" to nextCounterValue))
                }
                transaction.set(orderReference, orderDocument)
                transaction.set(
                    firestore.collection(CHATS).document(orderId),
                    mapOf(
                        "customerId" to uid,
                        "orderId" to orderId,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                PlacedOrder(orderId = orderId, shortId = shortId, totalAmountPaisa = total)
            }.await()
        }.fold(
            onSuccess = { UndalResult.Success(it) },
            onFailure = { error ->
                UndalResult.Failure(
                    (error as? OrderBlocked)?.message ?: UndalErrors.firestore(error),
                    error
                )
            }
        )

        if (outcome is UndalResult.Success) {
            // The live mirror is what every screen and the Worker's crons read:
            // it must exist the moment the order exists (C-069).
            runCatching {
                database.getReference(LIVE).child(outcome.value.orderId).setValue(
                    mapOf(
                        "customerId" to uid,
                        "status" to OrderStatus.PLACED.wire,
                        "updatedAt" to ServerValue.TIMESTAMP
                    )
                )
            }
            runCatching {
                database.getReference(CHAT_NODE).child(outcome.value.orderId).setValue(
                    mapOf(
                        "customerId" to uid,
                        "createdAt" to ServerValue.TIMESTAMP
                    )
                )
            }
            // The kitchen phone must ring even if this customer's app is closed
            // one second later, so the relay is queued through WorkManager.
            NotifyRetryWorker.enqueue(
                context = context,
                orderId = outcome.value.orderId,
                kind = NotifyRetryWorker.KIND_ORDER_CREATED
            )
        }
        return outcome
    }

    // ------------------------------------------------------------------- read
    /** The most recent orders of this customer; the rules cap a list query at 50. */
    fun observeMyOrders(limit: Long = 20L): Flow<List<OrderSummary>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val registration = firestore.collection(ORDERS)
            .whereEqualTo("customerId", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit.coerceIn(1L, 50L))
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().map { document -> document.toSummary() })
            }
        awaitClose { registration.remove() }
    }.catch { emit(emptyList()) }

    /** One order, live from Firestore and mirrored by the RTDB status flag. */
    fun observeOrder(orderId: String): Flow<OrderDetail?> {
        val firestoreFlow = callbackFlow {
            val registration = firestore.collection(ORDERS).document(orderId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.takeIf { it.exists() }?.toDetail())
                }
            awaitClose { registration.remove() }
        }.catch { emit(null) }

        val liveFlow = callbackFlow {
            val reference = database.getReference(LIVE).child(orderId).child("status")
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    trySend(snapshot.getValue(String::class.java))
                }

                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    trySend(null)
                }
            }
            reference.addValueEventListener(listener)
            awaitClose { reference.removeEventListener(listener) }
        }.catch { emit(null) }

        return combine(firestoreFlow, liveFlow) { detail, liveStatus ->
            detail?.copy(liveStatus = liveStatus)
        }
    }

    /**
     * Reorder (C-076): rebuilds cart lines from a past order and reports which
     * dishes are gone, so the cart the customer sees is honest about the menu.
     */
    suspend fun reorder(orderId: String, menu: MenuSnapshot): UndalResult<List<CartLine>> {
        val snapshot = runCatching {
            firestore.collection(ORDERS).document(orderId).get().await()
        }.getOrElse { return UndalResult.Failure(UndalErrors.firestore(it)) }

        if (!snapshot.exists()) return UndalResult.Failure("That order is no longer available.")

        @Suppress("UNCHECKED_CAST")
        val items = snapshot.get("items") as? List<Map<String, Any>> ?: emptyList()
        val lines = mutableListOf<CartLine>()
        val skipped = mutableListOf<String>()

        items.forEach { item ->
            val id = item["id"] as? String ?: return@forEach
            val isCombo = item["isCombo"] as? Boolean ?: false
            val quantity = (item["qty"] as? Number)?.toInt() ?: 1
            val name = (item["name"] as? String).orEmpty()

            if (isCombo) {
                val combo = menu.combo(id)
                if (combo == null || !combo.available) {
                    skipped += name
                    return@forEach
                }
                lines += CartLine(
                    id = combo.id,
                    name = combo.name,
                    unitAmountPaisa = combo.amountPaisa,
                    quantity = quantity,
                    imageUrl = combo.imageUrl,
                    isCombo = true
                )
            } else {
                val dish = menu.dish(id)
                if (dish == null || !dish.available) {
                    skipped += name
                    return@forEach
                }
                lines += CartLine(
                    id = dish.id,
                    name = dish.name,
                    unitAmountPaisa = dish.payablePaisa,
                    quantity = quantity,
                    imageUrl = dish.imageUrl,
                    veg = dish.veg,
                    spiceLevel = dish.spiceLevel
                )
            }
        }

        if (lines.isEmpty()) {
            return UndalResult.Failure(
                if (skipped.isEmpty()) {
                    "That order has no items left to repeat."
                } else {
                    "Nothing from that order is available today: ${skipped.joinToString(", ")}."
                }
            )
        }

        val note = if (skipped.isEmpty()) {
            "Cart rebuilt from ${snapshot.getString("shortId").orEmpty()}."
        } else {
            "Added ${lines.size} item(s). Not available today: ${skipped.joinToString(", ")}."
        }
        return UndalResult.Success(lines).also {
            lastReorderNote = note
        }
    }

    /** The message the reorder produced, read by the caller right after [reorder]. */
    @Volatile
    var lastReorderNote: String = ""
        private set

    private fun com.google.firebase.firestore.DocumentSnapshot.toSummary(): OrderSummary {
        @Suppress("UNCHECKED_CAST")
        val items = get("items") as? List<Map<String, Any>>
        val status = OrderStatus.fromWire(getString("status")) ?: OrderStatus.PLACED
        return OrderSummary(
            id = id,
            shortId = getString("shortId").orEmpty(),
            status = status,
            totalAmountPaisa = getLong("totalAmountPaisa") ?: 0L,
            itemCount = items?.size ?: 0,
            firstItemName = (items?.firstOrNull()?.get("name") as? String).orEmpty(),
            createdAt = getTimestamp("createdAt")?.toDate()?.toInstant(),
            prepEndsAt = getTimestamp("prepEndsAt")?.toDate()?.toInstant(),
            cancelNote = getString("cancelNote").orEmpty(),
            isActive = OrderStateMachine.isActive(status)
        )
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toDetail(): OrderDetail {
        @Suppress("UNCHECKED_CAST")
        val address = get("address") as? Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val rawItems = get("items") as? List<Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val rawTimeline = get("timeline") as? List<Map<String, Any>>

        val items = rawItems.orEmpty().map { item ->
            OrderItemLine(
                name = (item["name"] as? String).orEmpty(),
                quantity = (item["qty"] as? Number)?.toInt() ?: 1,
                unitAmountPaisa = (item["unitAmountPaisa"] as? Number)?.toLong() ?: 0L,
                lineTotalPaisa = (item["lineTotalAmountPaisa"] as? Number)?.toLong() ?: 0L,
                note = (item["note"] as? String).orEmpty()
            )
        }

        val timeline = rawTimeline.orEmpty().mapNotNull { entry ->
            val status = OrderStatus.fromWire(entry["status"] as? String) ?: return@mapNotNull null
            val stampMillis = when (val ts = entry["ts"]) {
                is Timestamp -> ts.toDate().time
                is Number -> ts.toLong() * 1000L
                else -> 0L
            }
            status to TimeDhaka.formatInstantDate(
                if (stampMillis > 0L) Instant.ofEpochMilli(stampMillis) else null
            )
        }

        // A cancelled order also carries its reason, which is not a timeline step.
        val cancelNote = getString("cancelNote").orEmpty()
        val vendorNote = getString("vendorNote").orEmpty()

        return OrderDetail(
            id = id,
            shortId = getString("shortId").orEmpty(),
            status = OrderStatus.fromWire(getString("status")) ?: OrderStatus.PLACED,
            liveStatus = null,
            customerName = getString("customerName").orEmpty(),
            phone = getString("phone").orEmpty(),
            addressTitle = (address?.get("title") as? String).orEmpty(),
            addressDesc = (address?.get("desc") as? String).orEmpty(),
            addressNote = (address?.get("note") as? String).orEmpty(),
            addressLat = (address?.get("lat") as? Number)?.toDouble() ?: 0.0,
            addressLng = (address?.get("lng") as? Number)?.toDouble() ?: 0.0,
            distanceKm = getDouble("distanceKm") ?: 0.0,
            items = items,
            subtotalAmountPaisa = getLong("subtotalAmountPaisa") ?: 0L,
            deliveryAmountPaisa = getLong("deliveryAmountPaisa") ?: 0L,
            discountAmountPaisa = getLong("discountAmountPaisa") ?: 0L,
            totalAmountPaisa = getLong("totalAmountPaisa") ?: 0L,
            couponCode = getString("couponCode").orEmpty(),
            note = getString("note").orEmpty(),
            statusNotes = listOf(cancelNote, vendorNote).filter { it.isNotBlank() },
            timeline = timeline,
            prepMinutes = getLong("prepMinutes")?.toInt(),
            prepEndsAt = getTimestamp("prepEndsAt")?.toDate()?.toInstant(),
            cancelNote = cancelNote,
            collectedAmountPaisa = getLong("collectedAmountPaisa"),
            createdAt = getTimestamp("createdAt")?.toDate()?.toInstant()
        )
    }

    private companion object {
        const val ORDERS = "orders"
        const val MENU_ITEMS = "menuItems"
        const val COMBOS = "combos"
        const val COUPONS = "coupons"
        const val COUNTERS = "counters"
        const val CHATS = "chats"
        const val LIVE = "orderLive"
        const val CHAT_NODE = "chats"
    }
}
