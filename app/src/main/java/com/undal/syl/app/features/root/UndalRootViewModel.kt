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
import kotlinx.coroutines.CoroutineExceptionHandler
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

    /**
     * Nothing in this class is allowed to end the process.
     *
     * An exception that leaves a `viewModelScope` job is uncaught, and an
     * uncaught exception on Android is the app closing itself — the exact symptom
     * of signing in on a new phone with a weak connection, where the router's
     * first reads (a token, then `vendor/{uid}`) are the first reads that device
     * has ever made. With this handler attached, a failure becomes one sentence
     * on the screen with a Try-again next to it.
     */
    private val failures = CoroutineExceptionHandler { _, error ->
        val message = com.undal.syl.app.core.util.UndalErrors.firestore(error, operation = "opening Undal")
        _errorMessage.value = message
        val current = _destination.value
        // A failure while the splash is still up has to become something the
        // person can act on; a failure anywhere else must not yank them out of
        // the screen they are using.
        if (current is Destination.Splash || current is Destination.Error) {
            _destination.value = Destination.Error(message)
        }
    }

    init {
        viewModelScope.launch(failures) {
            session.state.collect { state ->
                when (state) {
                    // The splash is the cold start's own screen; it is never a
                    // state this router paints, so a re-resolution cannot make the
                    // app flicker back to it.
                    is SessionState.Loading -> Unit

                    is SessionState.SignedOut -> {
                        _errorMessage.value = null
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
        viewModelScope.launch(failures) {
            // The screen the person is on stays on screen until the router knows
            // where it is sending them: the splash used to be painted here first,
            // which made every address save flash the splash and re-run the graph.
            val resolution = runCatching { roleGate.resolve() }.getOrElse { error ->
                RoleGate.RoleResolution.Failed(
                    com.undal.syl.app.core.util.UndalErrors.firestore(error, operation = "opening your account")
                )
            }
            when (resolution) {
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
        viewModelScope.launch(failures) {
            // Best effort, every step of it: a failed token write, a refused
            // notification permission or a service the ROM will not let us start
            // must never block the portal the person is trying to reach.
            runCatching { tokenRegistrar.register(uid = uid, isVendor = isVendor, subscribeToPromos = !isVendor) }

            // Who is signed in decides which alert routes exist on this phone: the
            // kitchen gets the live watcher, everyone else has it switched off.
            // The fact is also remembered, because a push can arrive in a fresh
            // process before the claim is refreshed.
            runCatching { progress.setKitchenDevice(isVendor) }
            runCatching { com.undal.syl.app.core.fcm.PushDeviceRole.remember(applicationContext(), uid, isVendor) }
            runCatching { KitchenWatchService.sync(applicationContext(), isVendor) }
        }
    }

    /** The kitchen signs out from store settings; the role gate re-resolves immediately. */
    fun signOut() {
        viewModelScope.launch(failures) {
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
        viewModelScope.launch(failures) {
            progress.markDiagnosticsDone()
            _destination.value = Destination.VendorConsole
        }
    }

    /**
     * Try again, from the screen that says something went wrong: the banner goes
     * away and the router re-runs. Nothing is remembered about the failure, so a
     * connection that came back is enough to move on.
     */
    fun onErrorAcknowledged() {
        _errorMessage.value = null
        resolveRole()
    }

    /** The root banner's own dismissal, when the person does not want to retry yet. */
    fun onErrorDismissed() {
        _errorMessage.value = null
    }

    /** A navigation the graph refused, reported instead of thrown. */
    fun onNavigationFailed(error: Throwable) {
        _errorMessage.value =
            com.undal.syl.app.core.util.UndalErrors.firestore(error, operation = "opening that screen")
    }

    fun emailAddress(): String? = auth.currentUser?.email
}
