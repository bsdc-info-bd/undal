package com.undal.syl.app.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.undal.syl.app.core.util.UndalResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The order conversation, on the Realtime Database (C-070 · C-071 · C-072).
 *
 * Chat lives in RTDB rather than Firestore for one reason: a message must appear
 * on the other phone in under a second, and RTDB is the cheaper, faster pipe for
 * a stream of tiny writes. Firestore keeps only the conversation's identity
 * (`chats/{orderId}` metadata), so the security rules can decide who may open it.
 *
 * "Unread" is kept on this device. The rules deliberately let a customer write
 * only messages sent by the customer, so the customer cannot stamp the kitchen's
 * replies as read on the server — and an unread badge that means "new since you
 * last looked at this chat on this phone" is both honest and useful.
 */
@Singleton
class ChatRepo @Inject constructor(
    private val database: FirebaseDatabase,
    private val auth: FirebaseAuth,
    private val dataStore: DataStore<Preferences>
) {

    data class Message(
        val id: String,
        val sender: String,
        val text: String,
        val tsMillis: Long
    ) {
        val fromKitchen: Boolean get() = sender == SENDER_VENDOR
    }

    /** Live messages for one order, oldest first. */
    fun observeMessages(orderId: String, limit: Int = 200): Flow<List<Message>> = callbackFlow {
        val reference = database.getReference(CHATS).child(orderId).child(MESSAGES)
            .orderByChild("ts")
            .limitToLast(limit)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = snapshot.children.mapNotNull { child ->
                    val sender = child.child("sender").getValue(String::class.java)
                        ?: return@mapNotNull null
                    val text = child.child("text").getValue(String::class.java)
                        ?: return@mapNotNull null
                    val stamp = child.child("ts").getValue(Long::class.java) ?: 0L
                    Message(id = child.key.orEmpty(), sender = sender, text = text, tsMillis = stamp)
                }.sortedBy { it.tsMillis }
                trySend(messages)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        reference.addValueEventListener(listener)
        awaitClose { reference.removeEventListener(listener) }
    }.catch { emit(emptyList()) }

    /** Whether the kitchen is typing right now (a value only, no history). */
    fun observeKitchenTyping(orderId: String): Flow<Boolean> = callbackFlow {
        val reference = database.getReference(CHATS).child(orderId).child(TYPING).child(SENDER_VENDOR)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.getValue(Boolean::class.java) ?: false)
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(false)
            }
        }
        reference.addValueEventListener(listener)
        awaitClose { reference.removeEventListener(listener) }
    }.catch { emit(false) }

    /** Publishes this customer's typing flag; the kitchen reads it under `typing/vendor`. */
    suspend fun setCustomerTyping(orderId: String, typing: Boolean): UndalResult<Unit> = runCatching {
        database.getReference(CHATS).child(orderId).child(TYPING).child("customer")
            .setValue(typing).await()
        Unit
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(it.message ?: "Could not update the typing indicator.", it) }
    )

    suspend fun send(orderId: String, text: String): UndalResult<Unit> {
        auth.currentUser?.uid ?: return UndalResult.Failure("Please sign in again — your session expired.")
        val body = text.trim()
        if (body.isEmpty()) return UndalResult.Failure("Type a message first.")
        if (body.length > MAX_MESSAGE_LENGTH) {
            return UndalResult.Failure("A message can hold up to $MAX_MESSAGE_LENGTH characters.")
        }
        return runCatching {
            database.getReference(CHATS).child(orderId).child(MESSAGES)
                .push()
                .setValue(
                    mapOf(
                        "sender" to SENDER_CUSTOMER,
                        "text" to body,
                        "ts" to ServerValue.TIMESTAMP
                    )
                )
                .await()
            setCustomerTyping(orderId, false)
            Unit
        }.fold(
            onSuccess = { UndalResult.Success(Unit) },
            onFailure = {
                UndalResult.Failure(
                    "The message did not send: ${it.message ?: "check your connection and try again"}. ",
                    it
                )
            }
        )
    }

    /** Milliseconds of the last moment this phone looked at the conversation. */
    fun lastReadFlow(orderId: String): Flow<Long> = dataStore.data.map { preferences ->
        preferences[readKey(orderId)] ?: 0L
    }

    /**
     * Marks the conversation as seen up to [atMillis].
     *
     * The stamp is the newest message time rather than simply "now", because the
     * message timestamps come from the server while this clock belongs to the
     * phone — and a phone that is two minutes behind the server would otherwise
     * keep showing an unread badge for a message it is already displaying.
     */
    suspend fun markRead(orderId: String, atMillis: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences -> preferences[readKey(orderId)] = atMillis }
    }

    /** How many kitchen messages arrived after the last look. */
    fun unreadCount(messages: List<Message>, lastReadMillis: Long): Int =
        messages.count { it.fromKitchen && it.tsMillis > lastReadMillis }

    private fun readKey(orderId: String) = longPreferencesKey("chat_last_read_$orderId")

    companion object {
        const val MAX_MESSAGE_LENGTH = 1000
        const val SENDER_CUSTOMER = "customer"
        const val SENDER_VENDOR = "vendor"

        private const val CHATS = "chats"
        private const val MESSAGES = "messages"
        private const val TYPING = "typing"
    }
}
