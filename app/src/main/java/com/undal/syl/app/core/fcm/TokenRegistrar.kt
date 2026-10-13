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
 * Customer devices also subscribe to the `promos_all` topic used by the daily
 * 18:00 Asia/Dhaka offer broadcast.
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

        if (subscribeToPromos) {
            // Fire-and-forget: a failed subscription must never block sign-in.
            runCatching { messaging.subscribeToTopic(PROMO_TOPIC).await() }
        }
        token
    }

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
