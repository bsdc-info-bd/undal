package com.undal.syl.app.features.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.undal.syl.app.core.background.KitchenWatchService
import com.undal.syl.app.core.background.OrderAlertController
import com.undal.syl.app.core.fcm.TokenRegistrar
import com.undal.syl.app.core.session.AuthSession
import com.undal.syl.app.core.session.OnboardingProgress
import com.undal.syl.app.core.session.SessionState
import com.undal.syl.app.core.session.RoleGate
import com.undal.syl.app.core.session.UndalRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app's opening decision: is anybody signed in, which portal do they belong
 * to, and has their profile (with the mandatory address) been completed?
 *
 * This is the only place that decides where a cold start lands, which keeps the
 * navigation graph free of business rules and stops the "logged-in customer who
 * never finished registration" trap from ever showing an empty home screen.
 */
@HiltViewModel
class UndalRootViewModel @Inject constructor(
    private val session: AuthSession,
    private val roleGate: RoleGate,
    private val tokenRegistrar: TokenRegistrar,
    private val progress: OnboardingProgress,
    private val auth: FirebaseAuth,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    sealed interface Destination {
        /** Still deciding — the branded splash is on screen. */
        data object Splash : Destination

        /** Nobody signed in. */
        data object Auth : Destination

        /** Signed-in customer who still has to pin an address. */
        data object CustomerLocation : Destination

        data object CustomerHome : Destination

        /** The kitchen device: diagnostics first, then the console. */
        data object VendorDiagnostics : Destination

        data object VendorConsole : Destination

        data class Error(val message: String) : Destination
    }

    private val _destination = MutableStateFlow<Destination>(Destination.Splash)
    val destination: StateFlow<Destination> = _destination.asStateFlow()

    private val _emailVerified = MutableStateFlow(false)
    val emailVerified: StateFlow<Boolean> = _emailVerified.asStateFlow()

    /** Set when the router could not decide where to send the person. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val draftAddress = progress.draftAddress

    init {
        viewModelScope.launch {
            session.state.collect { state ->
                when (state) {
                    is SessionState.Loading -> {
                        _destination.value = Destination.Splash
                    }

                    is SessionState.SignedOut -> {
                        _destination.value = Destination.Auth
                    }

                    is SessionState.SignedIn -> {
                        _emailVerified.value = state.user.emailVerified
                        registerDevice(state.user.uid, state.user.isVendorClaim)
                        resolveRole()
                    }
                }
            }
        }
    }

    /** Re-runs the router after sign-in, sign-out, address save or wizard finish. */
    fun resolveRole() {
        viewModelScope.launch {
            _destination.value = Destination.Splash
            when (val resolution = roleGate.resolve()) {
                is RoleGate.RoleResolution.NotSignedIn -> _destination.value = Destination.Auth

                is RoleGate.RoleResolution.NeedsProfile -> _destination.value = Destination.CustomerLocation

                is RoleGate.RoleResolution.Failed -> {
                    _errorMessage.value = resolution.message
                    _destination.value = Destination.Error(resolution.message)
                }

                is RoleGate.RoleResolution.Routed -> {
                    _destination.value = when (resolution.role) {
                        UndalRole.VENDOR -> {
                            // The kitchen sees the device wizard once, then the console
                            // for every later start (V-004).
                            if (progress.diagnosticsDone.first()) {
                                Destination.VendorConsole
                            } else {
                                Destination.VendorDiagnostics
                            }
                        }

                        UndalRole.CUSTOMER -> {
                            if (resolution.hasLocation) Destination.CustomerHome else Destination.CustomerLocation
                        }

                        UndalRole.UNKNOWN -> Destination.Error(
                            "This account has no role yet. Please contact the Undal kitchen."
                        )
                    }
                }
            }
        }
    }

    private fun registerDevice(uid: String, isVendor: Boolean) {
        viewModelScope.launch {
            // Best effort: a failed token write must never block the portal.
            tokenRegistrar.register(uid = uid, isVendor = isVendor, subscribeToPromos = !isVendor)

            // Who is signed in decides which alert routes exist on this phone: the
            // kitchen gets the live watcher, everyone else has it switched off.
            // The fact is also remembered, because a push can arrive in a fresh
            // process before the claim is refreshed.
            progress.setKitchenDevice(isVendor)
            com.undal.syl.app.core.fcm.PushDeviceRole.remember(applicationContext(), uid, isVendor)
            KitchenWatchService.sync(applicationContext(), isVendor)
        }
    }

    /** The kitchen signs out from store settings; the role gate re-resolves immediately. */
    fun signOut() {
        viewModelScope.launch {
            // Silence anything that is ringing and drop the watcher before the
            // session goes: a signed-out phone must not keep watching orders.
            KitchenWatchService.stop(applicationContext())
            OrderAlertController.stop()
            com.undal.syl.app.core.fcm.PushDeviceRole.clear(applicationContext())
            session.signOut()
            resolveRole()
        }
    }

    /** Application context, for services that outlive this screen. */
    private fun applicationContext(): android.content.Context = appContext


    fun onSignedIn() {
        resolveRole()
    }

    fun onAddressSaved() {
        resolveRole()
    }

    fun onDiagnosticsFinished() {
        viewModelScope.launch {
            progress.markDiagnosticsDone()
            _destination.value = Destination.VendorConsole
        }
    }

    fun onErrorAcknowledged() {
        resolveRole()
    }

    fun emailAddress(): String? = auth.currentUser?.email
}
