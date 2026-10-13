package com.undal.syl.app.data.repo

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-app notification inbox: `notifInbox/{uid}/items/{id}` (C-078 · V-109).
 *
 * Every push that reaches the device is also written here, so a customer who
 * swiped the notification away can still find the message, and the kitchen keeps
 * its own alert history even when the sound was muted by a system dialog.
 */
@Singleton
class NotifInboxRepo @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    data class InboxItem(
        val id: String,
        val title: String,
        val body: String,
        val type: String,
        val orderId: String?,
        val imageUrl: String?,
        val read: Boolean,
        val createdAtMillis: Long
    )

    suspend fun record(
        uid: String,
        title: String,
        body: String,
        type: String,
        orderId: String? = null,
        imageUrl: String? = null
    ): Result<Unit> = runCatching {
        val payload = mutableMapOf<String, Any>(
            "title" to title.take(120),
            "body" to body.take(400),
            "type" to type,
            "read" to false,
            "createdAt" to FieldValue.serverTimestamp()
        )
        if (orderId != null) payload["orderId"] = orderId
        if (imageUrl != null) payload["imageUrl"] = imageUrl
        firestore.collection(NOTIF_INBOX).document(uid).collection(ITEMS).add(payload).await()
        Unit
    }

    fun observe(uid: String, limit: Long = 80L): Flow<List<InboxItem>> = callbackFlow {
        val registration = firestore.collection(NOTIF_INBOX).document(uid).collection(ITEMS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val items = snapshot?.documents.orEmpty().map { document ->
                    InboxItem(
                        id = document.id,
                        title = document.getString("title").orEmpty(),
                        body = document.getString("body").orEmpty(),
                        type = document.getString("type").orEmpty(),
                        orderId = document.getString("orderId"),
                        imageUrl = document.getString("imageUrl"),
                        read = document.getBoolean("read") ?: false,
                        createdAtMillis = document.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    )
                }
                trySend(items)
            }
        awaitClose { registration.remove() }
    }

    suspend fun markRead(uid: String, itemId: String): Result<Unit> = runCatching {
        firestore.collection(NOTIF_INBOX).document(uid).collection(ITEMS).document(itemId)
            .update("read", true).await()
        Unit
    }

    suspend fun markAllRead(uid: String): Result<Unit> = runCatching {
        val snapshot = firestore.collection(NOTIF_INBOX).document(uid).collection(ITEMS)
            .whereEqualTo("read", false).limit(200).get().await()
        if (snapshot.isEmpty) return@runCatching Unit
        val batch = firestore.batch()
        snapshot.documents.forEach { batch.update(it.reference, "read", true) }
        batch.commit().await()
        Unit
    }

    private companion object {
        const val NOTIF_INBOX = "notifInbox"
        const val ITEMS = "items"
    }
}
