package com.undal.syl.app.core.session

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * **The email-verified flag is the other half of this file.** `FirebaseUser`
 * caches the account record it last downloaded, and clicking the verification
 * link in a mail app changes the record on *Google's* side, not in this process.
 * Nothing in the Firebase client watches for that: no listener fires, because
 * from the SDK's point of view nothing happened on this device. That is why a
 * customer who had just confirmed their address still read "your email is not
 * verified" — the app was faithfully showing a cached `false` from before.
 *
 * The answer is [refreshUser]: it reloads the record from the server, re-reads
 * the ID token's claims, and **republishes** the session so every screen holding
 * this state stops showing the old answer. [state] is a real [StateFlow] rather
 * than a callback flow for exactly that reason — the readers of this state are
 * ordinary screens, and a reload has to be able to reach them.
 */
@Singleton
class AuthSession @Inject constructor(
    private val auth: FirebaseAuth,
    private val messaging: com.google.firebase.messaging.FirebaseMessaging
) {

    val currentUser: FirebaseUser? get() = auth.currentUser

    val uid: String? get() = auth.currentUser?.uid

    fun isSignedIn(): Boolean = auth.currentUser != null

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)

    /** Continuous auth state for the navigation graph and for every banner. */
    val state: StateFlow<SessionState> = _state.asStateFlow()

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

    init {
        publish()
        auth.currentUser?.let { user ->
            readClaims(user)
            reconcileVerifiedOnce(user)
        }

        val authListener = FirebaseAuth.AuthStateListener { updated ->
            cachedClaims = emptyMap()
            publish()
            updated.currentUser?.let { user ->
                readClaims(user)
                reconcileVerifiedOnce(user)
            }
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
    }

    private fun publish() {
        _state.value = auth.currentUser.toSessionState()
    }

    private fun readClaims(user: FirebaseUser) {
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

    /**
     * One server reload per signed-in account per process, and only when the
     * cached answer says "not confirmed".
     *
     * This is the case the customer actually hits: they confirm their address in
     * a mail app, close the app out of habit, and open it again. Nothing on this
     * device knows what they did, so the cold start's first honest answer is the
     * one on the server — and until it arrives, every screen is showing the
     * cached `false` that the customer is complaining about.
     *
     * It costs one small request, once; a customer who has not confirmed gets the
     * same answer and the same banner, but it is now an answer rather than an
     * assumption. A confirmed account never reaches this code at all.
     */
    @Volatile
    private var reconciledForUid: String? = null

    private fun reconcileVerifiedOnce(user: FirebaseUser) {
        if (user.isEmailVerified) return
        if (reconciledForUid == user.uid) return
        reconciledForUid = user.uid
        runCatching {
            user.reload().addOnSuccessListener { publish() }
        }.onFailure { error ->
            android.util.Log.w(TAG, "the account record could not be reloaded", error)
        }
    }

    val isVendor: kotlinx.coroutines.flow.Flow<Boolean> =
        state.map { it is SessionState.SignedIn && it.user.isVendorClaim }.distinctUntilChanged()

    /**
     * Re-reads this account from Firebase and republishes the session.
     *
     * This is the only way the app can learn that something happened *outside*
     * it — the commonest case being an email address that was confirmed by
     * tapping a link in a mail app. Both halves are refreshed, because they
     * answer different questions:
     *
     *  · `reload()` fetches the account record, which is where `emailVerified`
     *    lives, and
     *  · a forced ID-token refresh re-reads the claims, which is where `vendor`
     *    lives and where `email_verified` will be when the rules ever look.
     *
     * Returns true when the reload reached Firebase. A phone with no connection
     * keeps the answer it had — which is honest, and is why the caller says when
     * the last check happened rather than pretending a stale answer is fresh.
     */
    suspend fun refreshUser(): Boolean {
        val user = auth.currentUser ?: return false
        val reloaded = runCatching { user.reload().await() }.isSuccess
        runCatching { user.getIdToken(true).await() }
            .onSuccess { token ->
                cachedClaims = token.claims ?: emptyMap()
            }
        publish()
        return reloaded
    }

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
