package com.undal.syl.app.core.session

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the kitchen signed in for a year (V-002).
 *
 * Firebase already persists the refresh token, but two things still kill a
 * kitchen session in the field: a refresh token revoked from the console, and a
 * device that stayed offline past the ID-token lifetime. This watchdog refreshes
 * the token on a schedule, proves the session is alive, and exposes the result
 * so the diagnostics screen can show the truth instead of a hopeful guess.
 */
@Singleton
class VendorSessionKeeper @Inject constructor(
    private val auth: FirebaseAuth,
    private val session: AuthSession
) {

    data class SessionHealth(
        val signedIn: Boolean,
        val vendorClaim: Boolean,
        val lastCheckAtMillis: Long,
        val lastError: String?,
        val secondsUntilExpiry: Long
    )

    private val _health = MutableStateFlow(
        SessionHealth(
            signedIn = false,
            vendorClaim = false,
            lastCheckAtMillis = 0L,
            lastError = null,
            secondsUntilExpiry = 0L
        )
    )
    val health: StateFlow<SessionHealth> = _health.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                verifySession()
                // Refresh every 20 minutes: far inside the one-hour token life,
                // cheap on data, and it re-checks the vendor claim each time.
                delay(REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    suspend fun verifySession(): SessionHealth {
        val user = auth.currentUser
        if (user == null) {
            return update(
                SessionHealth(
                    signedIn = false,
                    vendorClaim = false,
                    lastCheckAtMillis = System.currentTimeMillis(),
                    lastError = null,
                    secondsUntilExpiry = 0L
                )
            )
        }
        return runCatching {
            val token = user.getIdToken(true).await()
            val claims = token.claims
            val isVendor = claims?.get(AuthSession.VENDOR_CLAIM_KEY) == true
            val expiresAt = token.expirationTimestamp
            val secondsLeft = if (expiresAt > 0) (expiresAt - System.currentTimeMillis()) / 1000 else 0
            update(
                SessionHealth(
                    signedIn = true,
                    vendorClaim = isVendor,
                    lastCheckAtMillis = System.currentTimeMillis(),
                    lastError = null,
                    secondsUntilExpiry = secondsLeft
                )
            )
        }.getOrElse { error ->
            Log.w(TAG, "vendor session refresh failed", error)
            update(
                _health.value.copy(
                    signedIn = auth.currentUser != null,
                    lastCheckAtMillis = System.currentTimeMillis(),
                    lastError = com.undal.syl.app.core.util.UndalErrors.auth(error)
                )
            )
        }
    }

    private fun update(value: SessionHealth): SessionHealth {
        _health.value = value
        return value
    }

    private companion object {
        const val TAG = "UndalSession"
        const val REFRESH_INTERVAL_MILLIS = 20L * 60L * 1000L
    }
}
