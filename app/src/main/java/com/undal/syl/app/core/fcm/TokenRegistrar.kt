package com.undal.syl.app.core.fcm

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
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
    @Named("appVersionName") private val appVersionName: String
) {

    suspend fun register(uid: String, isVendor: Boolean, subscribeToPromos: Boolean): Result<String> = runCatching {
        val token = messaging.token.await()
        val document = firestore
            .collection(FCM_TOKENS).document(uid)
            .collection(TOKENS).document(token)

        document.set(
            mapOf(
                "platform" to "android",
                "appVersion" to appVersionName,
                "role" to if (isVendor) "vendor" else "customer",
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

        if (isVendor) {
            // The kitchen route. Deliberately not from the customer path: only a
            // device signed in as the kitchen may receive order alerts.
            runCatching { messaging.subscribeToTopic(AlertContract.KITCHEN_TOPIC).await() }
                .onFailure { subscribeFailure = it.message.orEmpty() }
        } else {
            // A phone that used to be the kitchen must stop receiving alert
            // fan-outs the moment a customer signs in on it.
            runCatching { messaging.unsubscribeFromTopic(AlertContract.KITCHEN_TOPIC).await() }
        }
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
        messaging.subscribeToTopic(AlertContract.KITCHEN_TOPIC).await()
        true
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
    }
}
