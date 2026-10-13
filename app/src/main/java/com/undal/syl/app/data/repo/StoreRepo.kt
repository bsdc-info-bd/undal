package com.undal.syl.app.data.repo

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.model.Campaign
import com.undal.syl.app.core.model.PromosConfig
import com.undal.syl.app.core.model.StoreConfig
import com.undal.syl.app.core.model.StoreOpenState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
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

    /** Live open/closed decision, recomputed from the document and the clock. */
    fun observeOpenState(): Flow<StoreOpenState> = observeStore().map { StoreOpenState.of(it) }

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
    }
}
