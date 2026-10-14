package com.undal.syl.app.data.repo

import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.undal.syl.app.core.model.PresenceDay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `presenceDays/{yyyy-mm-dd}` — the kitchen phone's connection history.
 *
 * One small document per Dhaka date, written by the Worker's five-minute
 * presence sweep and read here for the dashboard's offline/online analytics. The
 * read is a live listener over a bounded query (the last seven dates), so the
 * numbers on screen update as the sweep writes them, and the cost of the read
 * does not grow with the age of the shop.
 *
 * The collection is vendor-readable and Worker-written: see
 * `firebase/firestore.rules`. A customer app never sees it.
 */
@Singleton
class PresenceRepo @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    /**
     * The last [days] days, newest first.
     *
     * Ordered by the document id — which is the date itself — so Firestore
     * serves the range from the primary key and no composite index has to be
     * created for a query that runs on every dashboard open.
     */
    fun observeDays(days: Int = 7): Flow<List<PresenceDay>> = callbackFlow {
        val registration = firestore.collection(COLLECTION)
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
            .limit(days.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(
                    snapshot?.documents.orEmpty().map { document ->
                        PresenceDay.fromMap(document.id, document.data.orEmpty())
                    }
                )
            }
        awaitClose { registration.remove() }
    }.catch { emit(emptyList()) }

    private companion object {
        const val COLLECTION = "presenceDays"
    }
}
