package com.undal.syl.app.data.repo

import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.model.PresenceDay
import com.undal.syl.app.core.branch.BranchMembership
import com.undal.syl.app.core.branch.VendorBranch
import com.undal.syl.app.core.util.TimeDhaka
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The selected kitchen phone's connection history, written by the Worker.
 *
 * Each date is read as its own document instead of querying the collection.
 * Firestore rules are not filters: a collection query cannot prove that every
 * returned history document belongs to the caller's branch. Deterministic
 * document ids avoid that ambiguity, need no composite index, and still provide
 * live updates for the bounded seven-day dashboard.
 */
@Singleton
class PresenceRepo @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val membership: BranchMembership,
) {

    /** The last [days] Dhaka dates, newest first. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeDays(days: Int = 7): Flow<List<PresenceDay>> = membership.selection
        .flatMapLatest { branch -> observeBranchDays(branch, days) }

    private fun observeBranchDays(branch: VendorBranch, days: Int): Flow<List<PresenceDay>> {
        val today = TimeDhaka.today()
        val flows = (0 until days.coerceIn(1, 31)).map { offset ->
            val dateKey = today.minusDays(offset.toLong()).toString()
            val documentId = if (branch.legacySingleBranch) dateKey else "${branch.id}_$dateKey"
            observeDay(documentId, dateKey)
        }
        return combine(flows) { entries -> entries.filterNotNull() }
    }

    private fun observeDay(documentId: String, dateKey: String): Flow<PresenceDay?> = callbackFlow {
        val registration = firestore.collection(COLLECTION).document(documentId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(
                    snapshot?.takeIf { it.exists() }?.let { document ->
                        PresenceDay.fromMap(dateKey, document.data.orEmpty())
                    }
                )
            }
        awaitClose { registration.remove() }
    }.catch { emit(null) }

    private companion object {
        const val COLLECTION = "presenceDays"
    }
}
