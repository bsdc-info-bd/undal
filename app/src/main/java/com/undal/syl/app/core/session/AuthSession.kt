package com.undal.syl.app.core.session

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** What the app knows about the signed-in person, before any Firestore read. */
data class SessionUser(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
    val isVendorClaim: Boolean
)

sealed interface SessionState {
    /** Auth has not answered yet — the splash stays on screen. */
    data object Loading : SessionState

    /** Nobody is signed in. */
    data object SignedOut : SessionState

    /** Signed in; the role router still has to load the profile. */
    data class SignedIn(val user: SessionUser) : SessionState
}

/**
 * The single authority on "who is using this phone".
 *
 * The vendor role comes from the `vendor: true` custom claim that bootstrap set
 * through the Identity Toolkit REST API — not from anything the client can
 * write. The claim is refreshed on every token refresh, which Firebase does
 * every hour, so a revoked kitchen account loses access within the hour even if
 * the device is never restarted.
 */
@Singleton
class AuthSession @Inject constructor(
    private val auth: FirebaseAuth,
    private val messaging: com.google.firebase.messaging.FirebaseMessaging
) {

    val currentUser: FirebaseUser? get() = auth.currentUser

    val uid: String? get() = auth.currentUser?.uid

    fun isSignedIn(): Boolean = auth.currentUser != null

    /** Continuous auth state for the navigation graph. */
    val state: Flow<SessionState> = callbackFlow {
        trySend(auth.currentUser.toSessionState())
        val listener = FirebaseAuth.AuthStateListener { updated -> trySend(updated.currentUser.toSessionState()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    val isVendor: Flow<Boolean> = state.map { it is SessionState.SignedIn && it.user.isVendorClaim }.distinctUntilChanged()

    /**
     * Forces a fresh ID token so a claim change (vendor granted or revoked at
     * bootstrap) takes effect without waiting for the hourly refresh.
     */
    suspend fun refreshToken(): Boolean = runCatching {
        auth.currentUser?.getIdToken(true)?.await() != null
    }.getOrDefault(false)

    suspend fun signOut() {
        // unsubscribeFromTopic is best-effort: if the device is offline the
        // subscription simply persists until the next sign-in.
        runCatching {
            messaging.unsubscribeFromTopic(com.undal.syl.app.core.fcm.TokenRegistrar.PROMO_TOPIC).await()
        }
        auth.signOut()
    }

    private fun FirebaseUser?.toSessionState(): SessionState {
        if (this == null) return SessionState.SignedOut
        return SessionState.SignedIn(
            SessionUser(
                uid = uid,
                email = email,
                emailVerified = isEmailVerified,
                isVendorClaim = tokenClaims()?.get(VENDOR_CLAIM_KEY) == true
            )
        )
    }

    private fun FirebaseUser?.tokenClaims(): Map<String, Any>? =
        runCatching { this?.getIdToken(false)?.result?.claims ?: emptyMap() }.getOrNull()

    companion object {
        const val VENDOR_CLAIM_KEY = "vendor"
    }
}
