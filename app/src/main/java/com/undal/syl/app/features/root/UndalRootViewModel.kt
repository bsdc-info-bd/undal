package com.undal.syl.app.features.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.undal.syl.app.core.background.KitchenWatchService
import com.undal.syl.app.core.background.OrderAlertController
import com.undal.syl.app.core.background.PresenceHeartbeatWorker
import com.undal.syl.app.core.fcm.PushDeviceRole
import com.undal.syl.app.core.fcm.TokenRegistrar
import com.undal.syl.app.core.session.AuthSession
import com.undal.syl.app.core.session.OnboardingProgress
import com.undal.syl.app.core.session.SessionState
import com.undal.syl.app.core.session.RoleGate
import com.undal.syl.app.core.session.UndalRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * The resolution currently in flight, if any.
     *
     * A cold start asks for this more than once: the session emits when Firebase
     * restores the account, emits again when the ID token's claims land, and the
     * login screen asks a third time. Each ask costs a token read and two Firestore
     * reads, and two of them finishing out of order would paint a destination that
     * the third then painted over — which on screen is a flicker and on the network
     * is a phone in a dead spot spending its only good moment on a duplicate.
     * The last ask is the one that answers.
     */
    private var resolving: Job? = null

    /** Re-runs the router after sign-in, sign-out, address save or wizard finish. */
    fun resolveRole() {
        resolving?.cancel()
        resolving = viewModelScope.launch(failures) {
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
                    // The claim has now been read off the token, which is the
                    // authority. Correct the provisional registration above if it
                    // guessed differently — and it does guess differently on a
                    // cold start, because a token being fetched has no claims yet.
                    session.uid?.let { uid ->
                        registerDevice(uid, resolution.role == UndalRole.VENDOR)
                    }
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

    /**
     * Tells this phone which of the two it is.
     *
     * Called twice for a signed-in account, and both calls matter. The first is
     * immediate, so a phone that is offline still registers its push token and
     * still switches the kitchen's watcher off if it is a customer's. The second
     * comes from [resolveRole], which is the only place that has actually read the
     * claim off the token — and before that read existed, a kitchen phone spent
     * the first second of every cold start believing it was a customer's, which
     * switched its order watcher off and told the watchdog to disarm.
     */
    private fun registerDevice(uid: String, isVendor: Boolean) {
        viewModelScope.launch(failures) {
            if (registeredRole == RoleRegistration(uid, isVendor)) return@launch
            registeredRole = RoleRegistration(uid, isVendor)

            // Best effort, every step of it: a failed token write, a refused
            // notification permission or a service the ROM will not let us start
            // must never block the portal the person is trying to reach.
            runCatching { tokenRegistrar.register(uid = uid, isVendor = isVendor, subscribeToPromos = !isVendor) }

            // Who is signed in decides which alert routes exist on this phone: the
            // kitchen gets the live watcher, everyone else has it switched off.
            // The fact is also remembered, because a push can arrive in a fresh
            // process before the claim is refreshed — and because the watchdog,
            // the boot receiver and the fifteen-minute heartbeat all read it from
            // a place where they cannot wait for a token round trip.
            runCatching { progress.setKitchenDevice(isVendor) }
            runCatching { PushDeviceRole.remember(applicationContext(), uid, isVendor) }
            runCatching { KitchenWatchService.sync(applicationContext(), isVendor) }
            // The heartbeat job is the writer of last resort for the ten-minute
            // auto-offline rule, so it follows the role and not the launch: a
            // customer's phone that beat the kitchen's presence would hold a
            // closed store open.
            // On `IO`, not on the main thread: the first touch of WorkManager in a
            // process opens its database on the calling thread, and a login is the
            // last moment to spend a hundred milliseconds of the main thread on it.
            runCatching {
                withContext(Dispatchers.IO) {
                    if (isVendor) PresenceHeartbeatWorker.ensureScheduled(applicationContext())
                    else PresenceHeartbeatWorker.cancel(applicationContext())
                }
            }
        }
    }

    /** The role this phone has already been told it is, so a repeat costs nothing. */
    private data class RoleRegistration(val uid: String, val isVendor: Boolean)

    @Volatile
    private var registeredRole: RoleRegistration? = null

    /** The kitchen signs out from store settings; the role gate re-resolves immediately. */
    fun signOut() {
        viewModelScope.launch(failures) {
            registeredRole = null
            // Silence anything that is ringing and drop the watcher before the
            // session goes: a signed-out phone must not keep watching orders.
            KitchenWatchService.stop(applicationContext())
            OrderAlertController.stop()
            PushDeviceRole.clear(applicationContext())
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
