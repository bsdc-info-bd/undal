package com.undal.syl.app.data.repo

import java.util.Locale
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
import com.undal.syl.app.core.telemetry.UndalTelemetry
import com.undal.syl.app.core.model.MenuSnapshot
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.net.WorkerApi
import org.json.JSONArray
import org.json.JSONObject
import com.undal.syl.app.core.model.StoreOpenState
import com.undal.syl.app.core.util.CouponMath
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalErrors
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.core.util.Validators
import com.undal.syl.app.core.util.onSuccess
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
    private val telemetry: UndalTelemetry,
    private val worker: WorkerApi,
    @ApplicationContext private val context: Context
) {

    /** Everything needed to place one order. */
    data class OrderDraft(
        val store: StoreConfig,
        val branchId: String = "default",
        val branchName: String = "Undal HomeKitchen",
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
        /**
         * The note the live mirror carried when the status arrived.
         *
         * A cancellation is written in two places: the order document (with
         * `cancelNote`) and the Realtime Database mirror (with `vendorNote`). The
         * mirror lands first, by design — it is what makes the customer's screen
         * move in under a second — so between the two writes the tracking page has
         * a status of "cancelled" and no reason to print with it. This field is
         * that reason, and the page shows it until the document catches up.
         */
        val liveNote: String,
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

    /**
     * Places an order and tells the owner's dashboard how it went (P-024, P-025).
     * The trace covers the whole write — the counter, the live price re-read, the
     * order document, the coupon — because that is the stretch a customer spends
     * watching a spinner.
     */
    suspend fun placeOrder(draft: OrderDraft): UndalResult<PlacedOrder> {
        val trace = telemetry.beginOrderSubmit()
        val outcome = placeOrderTraced(draft)
        outcome.onSuccess { placed ->
            telemetry.placeOrder(placed.orderId, draft.lines.sumOf { it.quantity }, placed.totalAmountPaisa)
            telemetry.stopTrace(trace, succeeded = true)
        }
        if (outcome is UndalResult.Failure) {
            telemetry.recordNonFatal("place_order", outcome.cause)
            telemetry.stopTrace(trace, succeeded = false)
        }
        return outcome
    }

    private suspend fun placeOrderTraced(draft: OrderDraft): UndalResult<PlacedOrder> {
        if (auth.currentUser?.uid == null) {
            return UndalResult.Failure("Please sign in again — your session expired.")
        }
        if (draft.branchId.isBlank()) {
            return UndalResult.Failure("We could not confirm your branch. Refresh your delivery address and try again.")
        }
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

        // The Worker is the sole checkout authority. It resolves the pin again,
        // verifies branch coverage/hours, reads every live price and computes all
        // money before reserving the order number and alerting that branch. This
        // also removes the old client-transaction privilege from Firestore rules.
        val lines = JSONArray()
        draft.lines.forEach { line ->
            lines.put(JSONObject().apply {
                put("id", line.id)
                put("qty", line.quantity)
                put("kind", if (line.isCombo) "combo" else "dish")
                if (line.note.isNotBlank()) put("note", line.note.trim().take(140))
            })
        }
        val payload = JSONObject()
            .put("source", "android")
            .put("branchId", draft.branchId)
            .put("customerName", draft.customerName.trim())
            .put("phone", phone)
            .put("lines", lines)
            .put("couponCode", draft.couponCode.trim().uppercase(Locale.ENGLISH))
            .put("note", draft.note.trim().take(300))
            .put("address", JSONObject().apply {
                put("lat", draft.address.lat)
                put("lng", draft.address.lng)
                put("title", draft.address.title)
                put("desc", draft.address.desc)
                put("note", draft.address.note)
            })

        return worker.placeStoreOrder(payload).fold(
            onSuccess = { answer ->
                val order = answer.optJSONObject("order")
                    ?: return@fold UndalResult.Failure("The order was accepted but its receipt was incomplete. Check order history before retrying.")
                val id = order.optString("id")
                val shortId = order.optString("shortId")
                if (id.isBlank() || shortId.isBlank()) {
                    UndalResult.Failure("The order receipt was incomplete. Check order history before retrying.")
                } else {
                    UndalResult.Success(
                        PlacedOrder(
                            orderId = id,
                            shortId = shortId,
                            totalAmountPaisa = order.optLong("totalPaisa"),
                        )
                    )
                }
            },
            onFailure = { error ->
                UndalResult.Failure(error.message ?: "The order could not be placed. Please try again.", error)
            },
        )
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

        // The mirror is read as a *node*, not as its `status` child, because a
        // cancellation carries its reason in the same write. Reading only the
        // status was why the tracking page could say "cancelled" with no reason
        // for the second or two before the order document committed.
        val liveFlow = callbackFlow {
            val reference = database.getReference(LIVE).child(orderId)
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    trySend(
                        LiveMirror(
                            status = snapshot.child("status").getValue(String::class.java),
                            vendorNote = snapshot.child("vendorNote").getValue(String::class.java).orEmpty()
                        )
                    )
                }

                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    trySend(null)
                }
            }
            reference.addValueEventListener(listener)
            awaitClose { reference.removeEventListener(listener) }
        }.catch { emit(null) }

        return combine(firestoreFlow, liveFlow) { detail, live ->
            detail?.copy(
                liveStatus = live?.status,
                liveNote = live?.vendorNote.takeUnless { it.isNullOrBlank() } ?: detail.liveNote
            )
        }
    }

    /** What the Realtime Database's live mirror says about one order. */
    private data class LiveMirror(val status: String?, val vendorNote: String)

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
            liveNote = getString("cancelNote").orEmpty(),
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

    /**
     * The shared rule's refusal, said the way the customer needs to hear it —
     * "remove it or add more", never a bare "invalid coupon".
     *
     * A refusal that names its fact is one the customer can act on: the date a
     * code ended, the days it runs, the taka still needed. Without the date,
     * "expired" on a promotion the kitchen swears is live is an argument, not an
     * answer — and it is the same sentence the web checkout writes, from
     * `couponSentence` in the Worker.
     */
    private fun couponRefusal(reason: String, coupon: CouponMath.Coupon, subtotalPaisa: Long): String =
        when (reason) {
            "switched off" -> "was switched off. Remove it to continue."
            "not set up correctly" -> "is not set up correctly — please tell the kitchen."
            "expired" -> {
                // The date and the minute, in Dhaka — what the web checkout prints.
                val ended = CouponMath.expiryMoment(coupon.expiresAtMillis)
                if (ended.isBlank()) "expired. Remove it to continue."
                else "expired on $ended. Remove it to continue."
            }
            "not today" -> "only works on ${coupon.daysOfWeek.joinToString(", ")}. Remove it to continue."
            "outside its hours" -> "only works between ${coupon.startsAt} and ${coupon.endsAt}."
            "used up" ->
                if (coupon.usageLimit > 0L) "has been used its full ${coupon.usageLimit} times. Sorry about that."
                else "has just been used its last time. Sorry about that."
            "basket too small" -> "needs ${Money.format(coupon.minSubtotalAmountPaisa)} of food — add " +
                "${Money.format(coupon.minSubtotalAmountPaisa - subtotalPaisa)} more, or remove it."
            else -> "does not change this total."
        }

    /**
     * The refusal when the kitchen is not taking orders, with the time it opens —
     * the sentence `closedSentence` in the Worker gives the web checkout.
     */
    private fun closedSentence(state: StoreOpenState): String = when (state.reason) {
        StoreOpenState.Reason.AUTO_CLOSED ->
            "The kitchen's phone is offline, so orders are paused for a few minutes. Your cart is saved — " +
                "please try again shortly."
        StoreOpenState.Reason.OUTSIDE_HOURS ->
            "The kitchen is closed right now — ${state.nextOpeningLine.replaceFirstChar { it.lowercase() }}. " +
                "Your cart is saved until then."
        else -> "The kitchen is not taking orders right now: ${state.detail} Your cart is saved."
    }

    private companion object {
        const val ORDERS = "orders"
        const val MENU_ITEMS = "menuItems"
        const val COMBOS = "combos"
        const val COUPONS = "coupons"
        const val COUNTERS = "counters"
        const val CONFIG = "config"
        const val STORE = "store"
        const val CHATS = "chats"
        const val LIVE = "orderLive"
        const val CHAT_NODE = "chats"
    }
}
