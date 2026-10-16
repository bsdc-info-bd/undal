package com.undal.syl.app.data.repo

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.model.Campaign
import com.undal.syl.app.core.model.PromosConfig
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.model.StoreOpenState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `config/store`, `config/promos` and `campaigns/{id}` (C-021 · C-022 · C-023 · C-027).
 *
 * All three documents are world-readable on purpose: the store front must answer
 * "are they cooking, what does delivery cost, and is there an offer" without the
 * customer signing in, and it must answer again the moment the owner changes it.
 */
@Singleton
class StoreRepo @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    /** Live store document. Emits [StoreConfig.unloaded] before the first snapshot. */
    fun observeStore(): Flow<StoreConfig> = callbackFlow {
        val registration = firestore.collection(CONFIG).document(STORE).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot.toStoreConfig())
        }
        awaitClose { registration.remove() }
    }.catch { emit(StoreConfig.unloaded()) }

    /**
     * The store document, emitted on every change *and* at every minute boundary.
     *
     * Whether the store is open is a function of the clock as well as of the
     * document: at 22:00 nothing in Firestore changes, yet the store closes. A
     * screen fed by [observeStore] alone went on saying "Open now" until the
     * document next changed — which, for a kitchen that keeps its switch on and
     * lets the schedule work, might be never.
     */
    fun observeStoreOnTheClock(): Flow<StoreConfig> =
        combine(observeStore(), minuteTicks()) { store, _ -> store }

    /** Live open/closed decision, recomputed from the document and the clock. */
    fun observeOpenState(): Flow<StoreOpenState> = observeStoreOnTheClock().map { StoreOpenState.of(it) }

    /** One tick now, then one just after each wall-clock minute begins. */
    private fun minuteTicks(): Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(MINUTE_MILLIS - now % MINUTE_MILLIS + TICK_SLACK_MILLIS)
        }
    }

    fun observePromos(): Flow<PromosConfig?> = callbackFlow {
        val registration = firestore.collection(CONFIG).document(PROMOS).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.let { document -> PromosConfig.fromMap(document.data.orEmpty()) })
        }
        awaitClose { registration.remove() }
    }.catch { emit(null) }

    /**
     * Campaigns the customer can use right now. Expired ones are filtered on the
     * phone rather than in the query, because a single kitchen produces a handful
     * of campaigns and filtering here avoids an index nobody would maintain.
     */
    fun observeLiveCampaigns(): Flow<List<Campaign>> = callbackFlow {
        val registration = firestore.collection(CAMPAIGNS).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val now = System.currentTimeMillis()
            val live = snapshot?.documents.orEmpty()
                .mapNotNull { document ->
                    Campaign.fromDocument(document.id, document.data.orEmpty())
                        ?.takeIf { it.isLive(now) }
                }
            trySend(live)
        }
        awaitClose { registration.remove() }
    }.catch { emit(emptyList()) }

    private fun DocumentSnapshot?.toStoreConfig(): StoreConfig {
        val map = this?.data ?: return StoreConfig.unloaded()
        return StoreConfig.fromMap(map)
    }

    companion object {
        private const val CONFIG = "config"
        private const val STORE = "store"
        private const val PROMOS = "promos"
        private const val CAMPAIGNS = "campaigns"
        private const val MINUTE_MILLIS = 60_000L
        /** Lands each tick just inside the new minute, never a hair before it. */
        private const val TICK_SLACK_MILLIS = 250L
    }
}
