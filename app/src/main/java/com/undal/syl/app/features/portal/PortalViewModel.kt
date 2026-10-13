package com.undal.syl.app.features.portal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.undal.syl.app.core.background.PresenceHeartbeat
import com.undal.syl.app.core.background.PresenceHeartbeatWorker
import com.undal.syl.app.core.background.TokenRefreshWorker
import com.undal.syl.app.core.fcm.TokenRegistrar
import com.undal.syl.app.core.session.AuthSession
import com.undal.syl.app.core.session.VendorSessionKeeper
import com.undal.syl.app.data.repo.AuthRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared state for the two portal landing screens while the full consoles are
 * built: who is signed in, whether the address is complete, whether notifications
 * are allowed, how fresh the kitchen heartbeat is, and the device token tail.
 *
 * This is not decoration — these are the five facts that decide whether Undal
 * works on this phone, and both portals show them honestly.
 */
@HiltViewModel
class PortalViewModel @Inject constructor(
    private val session: AuthSession,
    private val auth: FirebaseAuth,
    private val authRepo: AuthRepo,
    private val registrar: TokenRegistrar,
    private val sessionKeeper: VendorSessionKeeper,
    private val heartbeat: PresenceHeartbeat
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val greeting: String = "Undal",
        val email: String = "",
        val emailVerified: Boolean = true,
        val addressSummary: String = "",
        val notificationsEnabled: Boolean = true,
        val tokenTail: String = "",
        val sessionSummary: String = "",
        val heartbeatLabel: String = "checking…",
        val infoMessage: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // One heartbeat watcher for the life of the screen, not one per refresh.
        observeHeartbeat()
    }

    fun refresh(context: Context) {
        viewModelScope.launch {
            val user = auth.currentUser
            val location = authRepo.loadProfileLocation()
            val name = authRepo.loadProfileName().orEmpty()
            val health = sessionKeeper.verifySession()
            val token = registrar.currentToken()

            _state.value = _state.value.copy(
                loading = false,
                greeting = if (name.isBlank()) "Welcome to Undal" else "Hello, ${name.substringBefore(' ')}",
                email = user?.email.orEmpty(),
                emailVerified = user?.isEmailVerified ?: true,
                addressSummary = location?.let { "${it.title} · ${it.geohashLabel.ifBlank { "${it.lat}, ${it.lng}" }}" }
                    .orEmpty(),
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                tokenTail = token?.takeLast(8).orEmpty(),
                sessionSummary = when {
                    !health.signedIn -> "Not signed in"
                    health.vendorClaim -> "Kitchen account verified"
                    else -> "Customer account"
                },
                infoMessage = health.lastError
            )

            // A signed-in kitchen account keeps the phone reachable while the
            // console is open; the fifteen-minute worker covers the rest (V-006).
            if (health.vendorClaim) {
                PresenceHeartbeat.startService(context)
            }
        }
    }

    private fun observeHeartbeat() {
        viewModelScope.launch {
            heartbeat.observe().collect { presence ->
                val lastSeen = presence?.lastSeen ?: 0L
                _state.value = _state.value.copy(
                    heartbeatLabel = if (lastSeen == 0L) "never" else
                        PresenceHeartbeat.describeAge(System.currentTimeMillis() - lastSeen)
                )
            }
        }
    }

    fun resendVerification() {
        viewModelScope.launch {
            val result = authRepo.resendVerificationEmail()
            _state.value = _state.value.copy(
                infoMessage = when (result) {
                    is com.undal.syl.app.core.util.UndalResult.Success -> "Verification email sent again."
                    is com.undal.syl.app.core.util.UndalResult.Failure -> result.message
                }
            )
        }
    }

    /** Signs out and stops every device-side process that belongs to the kitchen. */
    fun signOut(context: Context) {
        viewModelScope.launch {
            auth.currentUser?.uid?.let { uid -> registrar.unregister(uid) }
            PresenceHeartbeat.stopService(context)
            PresenceHeartbeatWorker.cancel(context)
            heartbeat.markOffline()
            session.signOut()
        }
    }

    /** Kicks the token + heartbeat path again, used by the "send heartbeat now" button. */
    fun refreshDeviceRegistration(context: Context) {
        TokenRefreshWorker.enqueue(context)
        PresenceHeartbeat.startService(context)
        refresh(context)
    }
}
