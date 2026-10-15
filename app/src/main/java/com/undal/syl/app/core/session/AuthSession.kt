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

    /**
     * The claims of the ID token Firebase last handed this phone, empty until it
     * has handed one over.
     *
     * This used to be read with `getIdToken(false).result`, which is not a read
     * at all: `Task.getResult()` throws `IllegalStateException` unless the task
     * has already finished, and a token being fetched has not. The throw was
     * swallowed, so every cold start called the kitchen a customer — and the
     * router then asked the kitchen's order watcher to stop, through a route
     * that killed the process. The claims are taken from the task's own callback
     * now, published when they land, and read synchronously by everything that
     * cannot suspend.
     */
    @Volatile
    private var cachedClaims: Map<String, Any> = emptyMap()

    /**
     * What the token says about this phone, for the paths that run with no
     * coroutine to wait in: the alert watchdog deciding whether to revive the
     * kitchen, the boot receiver deciding the same thing before anyone has
     * opened the app. Both were reading a `Task` that had not finished.
     */
    val vendorClaim: Boolean get() = cachedClaims[VENDOR_CLAIM_KEY] == true

    /** Continuous auth state for the navigation graph. */
    val state: Flow<SessionState> = callbackFlow {
        fun publish() {
            trySend(auth.currentUser.toSessionState())
        }

        fun readClaims(user: FirebaseUser) {
            runCatching {
                user.getIdToken(false).addOnSuccessListener { token ->
                    cachedClaims = token.claims ?: emptyMap()
                    // Published again with the claim now in hand: the first
                    // emission of a cold start cannot know it, and the second one
                    // is what the router acts on.
                    publish()
                }
            }.onFailure { error ->
                android.util.Log.w(TAG, "the ID token's claims could not be read", error)
            }
        }

        publish()
        auth.currentUser?.let { readClaims(it) }

        val authListener = FirebaseAuth.AuthStateListener { updated ->
            cachedClaims = emptyMap()
            publish()
            updated.currentUser?.let { readClaims(it) }
        }
        // `IdTokenListener.onIdTokenChanged` is handed the *auth*, not the user
        // — the same shape as `AuthStateListener`, and the reason this fires on
        // the hourly refresh as well as on sign-in. Reading the user back off it
        // keeps one `readClaims` for both listeners.
        val tokenListener = FirebaseAuth.IdTokenListener { refreshed ->
            refreshed.currentUser?.let { readClaims(it) }
        }
        auth.addAuthStateListener(authListener)
        auth.addIdTokenListener(tokenListener)
        awaitClose {
            auth.removeAuthStateListener(authListener)
            auth.removeIdTokenListener(tokenListener)
        }
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
                isVendorClaim = vendorClaim
            )
        )
    }

    companion object {
        private const val TAG = "UndalSession"
        const val VENDOR_CLAIM_KEY = "vendor"
    }
}
