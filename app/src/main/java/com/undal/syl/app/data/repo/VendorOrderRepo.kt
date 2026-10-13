package com.undal.syl.app.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.undal.syl.app.core.model.VendorOrder
import com.undal.syl.app.core.util.Money
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every read the kitchen makes (V-015 · V-021 · V-023 · V-045 · V-046 · V-109).
 *
 * The queue is one realtime listener over `orders`, started at midnight Dhaka
 * time. Firestore's own persistence means the kitchen sees the last known queue
 * with no signal at all, and the `fromCache` flag lets the screens say so instead
 * of pretending the list is live.
 */
@Singleton
class VendorOrderRepo @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val notifInboxRepo: NotifInboxRepo,
    private val dataStore: DataStore<Preferences>
) {

    data class Feed(
        val orders: List<VendorOrder> = emptyList(),
        val fromCache: Boolean = false
    )

    /** Orders created since [startMillis], newest first, live. */
    fun observeSince(startMillis: Long, limit: Long = 120L): Flow<Feed> = callbackFlow {
        val registration = firestore.collection(ORDERS)
            .whereGreaterThanOrEqualTo("createdAt", Timestamp(Date(startMillis)))
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val orders = snapshot?.documents.orEmpty().mapNotNull { document ->
                    VendorOrder.fromDocument(document.id, document.data.orEmpty())
                }
                trySend(Feed(orders = orders, fromCache = snapshot?.metadata?.isFromCache == true))
            }
        awaitClose { registration.remove() }
    }.catch { emit(Feed()) }

    /** Today, from midnight Asia/Dhaka (V-011 … V-013). */
    fun observeToday(): Flow<Feed> = observeSince(VendorOrder.todayStartMillis())

    /** Today plus the six days before it, for the week chart (V-014). */
    fun observeWeek(): Flow<Feed> = observeSince(VendorOrder.todayStartMillis() - WEEK_BACK_MILLIS)

    /** One order, live, for the detail screen. */
    fun observeOrder(orderId: String): Flow<VendorOrder?> = callbackFlow {
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
                trySend(VendorOrder.fromDocument(snapshot.id, snapshot.data.orEmpty()))
            }
        awaitClose { registration.remove() }
    }.catch { emit(null) }

    /** Ids that are still moving — the dashboard counts these (V-013). */
    fun observeOpenIds(): Flow<Set<String>> = observeToday().map { feed ->
        feed.orders.filter { it.isOpen }.map { it.id }.toSet()
    }

    /**
     * Appends this order to the kitchen's own alert history exactly once
     * (V-109). The record lives in the vendor's own `notifInbox`, which the
     * rules let an owner write, so the history survives a reinstall and never
     * needs the Worker to remember anything.
     */
    suspend fun recordAlertIfNew(order: VendorOrder) {
        val uid = auth.currentUser?.uid ?: return
        val seen = dataStore.data.first()[ALERT_IDS].orEmpty()
        if (order.id in seen) return
        val recorded = notifInboxRepo.record(
            uid = uid,
            title = "New order ${order.shortId.ifBlank { order.id.takeLast(4) }}",
            body = "${Money.format(order.totalAmountPaisa)} · ${order.linesSummary}",
            type = "vendor_alert",
            orderId = order.id
        )
        if (recorded.isSuccess) {
            dataStore.edit { preferences ->
                preferences[ALERT_IDS] = (preferences[ALERT_IDS].orEmpty() + order.id)
                    .toList()
                    .takeLast(HISTORY_LIMIT)
                    .toSet()
            }
        }
    }

    /**
     * The customer's profile photo, when they uploaded one (V-026). The security
     * rules let the kitchen read any user document, so no extra plumbing is
     * needed for one extra picture on the order card.
     */
    suspend fun customerPhoto(customerId: String): String? = runCatching {
        if (customerId.isBlank()) return@runCatching null
        firestore.collection(USERS).document(customerId).get().await().getString(PHOTO)
    }.getOrNull()

    companion object {
        private const val ORDERS = "orders"
        private const val USERS = "users"
        private const val PHOTO = "photoUrl"
        private const val WEEK_BACK_MILLIS = 6L * 24L * 60L * 60L * 1000L
        private const val HISTORY_LIMIT = 60
        private val ALERT_IDS = stringSetPreferencesKey("vendor_alert_ids")
    }
}
