package com.undal.syl.app.core.fcm

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.undal.syl.app.core.branch.BranchMembership
import kotlinx.coroutines.flow.first
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Device registration for push (G1 · J · V-005).
 *
 * The token is stored at `fcmTokens/{uid}/tokens/{token}` — one document per
 * device, self-write only per the security rules — so a customer or the kitchen
 * can be reached on every phone they own, and the Worker can delete a token the
 * moment FCM reports it dead.
 *
 * Two topic subscriptions ride along, and both exist for the same reason: a
 * topic does not need the server to find the device's account.
 *
 *   · customer devices subscribe to `promos_all` (the daily 18:00 broadcast);
 *   · **kitchen devices subscribe to `undal_kitchen`**, which is how an order
 *     alert reaches the kitchen even when the server's `vendorUid()` lookup
 *     returns nothing — the failure that used to silence every order silently.
 *
 * The registration is verified, not assumed: the token document is read back
 * after the write. Registration used to be fire-and-forget, so a security-rule
 * refusal or an offline moment left a device that believed it was reachable and
 * a kitchen that never rang.
 */
@Singleton
class TokenRegistrar @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messaging: FirebaseMessaging,
    private val membership: BranchMembership,
    private val dataStore: DataStore<Preferences>,
    @Named("appVersionName") private val appVersionName: String
) {

    suspend fun register(uid: String, isVendor: Boolean, subscribeToPromos: Boolean): Result<String> = runCatching {
        val token = messaging.token.await()
        val branches = if (isVendor) membership.availableBranches() else emptyList()
        val branchTopics = branches.map { branch -> "undal_branch_" + branch.id.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-").take(48) }.toSet()
        val document = firestore
            .collection(FCM_TOKENS).document(uid)
            .collection(TOKENS).document(token)

        document.set(
            mapOf(
                "platform" to "android",
                "appVersion" to appVersionName,
                "role" to if (isVendor) "vendor" else "customer",
                "branchIds" to branches.map { it.id },
                "topics" to branchTopics.toList(),
                "deviceModel" to android.os.Build.MODEL,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()

        // Read it back. A token the server cannot see is a phone that will not
        // ring, and only this round trip can tell the difference.
        if (!document.get().await().exists()) {
            error("the device token was not accepted by the database")
        }

        if (subscribeToPromos) {
            // Fire-and-forget: a failed subscription must never block sign-in.
            runCatching { messaging.subscribeToTopic(PROMO_TOPIC).await() }
        }

        val previousTopics = dataStore.data.first()[BRANCH_TOPICS].orEmpty()
        (previousTopics - branchTopics).forEach { topic ->
            runCatching { messaging.unsubscribeFromTopic(topic).await() }
        }
        if (isVendor) {
            branchTopics.forEach { topic ->
                runCatching { messaging.subscribeToTopic(topic).await() }
                    .onFailure { subscribeFailure = it.message.orEmpty() }
            }
            // The unscoped topic is only the pre-migration/default fallback.
            if (branches.any { it.id == "default" }) {
                runCatching { messaging.subscribeToTopic(AlertContract.KITCHEN_TOPIC).await() }
                    .onFailure { subscribeFailure = it.message.orEmpty() }
            } else {
                runCatching { messaging.unsubscribeFromTopic(AlertContract.KITCHEN_TOPIC).await() }
            }
        } else {
            runCatching { messaging.unsubscribeFromTopic(AlertContract.KITCHEN_TOPIC).await() }
        }
        dataStore.edit { it[BRANCH_TOPICS] = if (isVendor) branchTopics else emptySet() }
        token
    }

    /** Why the kitchen-topic subscription failed on the last attempt, if it did. */
    var subscribeFailure: String? = null
        private set

    /**
     * Subscribes again and reports whether the subscription is in place. Safe to
     * call as often as the readiness check likes: subscribing twice is a no-op.
     */
    suspend fun ensureKitchenSubscription(): Boolean = runCatching {
        val branches = membership.availableBranches()
        branches.forEach { branch ->
            val topic = "undal_branch_" + branch.id.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(48)
            messaging.subscribeToTopic(topic).await()
        }
        if (branches.any { it.id == "default" }) {
            messaging.subscribeToTopic(AlertContract.KITCHEN_TOPIC).await()
        }
        branches.isNotEmpty()
    }.getOrDefault(false)

    suspend fun currentToken(): String? = runCatching { messaging.token.await() }.getOrNull()

    suspend fun unregister(uid: String): Result<Unit> = runCatching {
        val token = messaging.token.await()
        firestore.collection(FCM_TOKENS).document(uid).collection(TOKENS).document(token).delete().await()
        Unit
    }

    companion object {
        const val FCM_TOKENS = "fcmTokens"
        const val TOKENS = "tokens"
        const val PROMO_TOPIC = "promos_all"
        private val BRANCH_TOPICS = stringSetPreferencesKey("vendor_branch_topics")
    }
}
