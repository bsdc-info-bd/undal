package com.undal.syl.app.features.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.background.KitchenWatchService
import com.undal.syl.app.core.background.OrderAlertController
import com.undal.syl.app.core.background.PresenceHeartbeat
import com.undal.syl.app.core.background.PresenceHeartbeatWorker
import com.undal.syl.app.core.maps.UndalLocation
import com.undal.syl.app.core.net.CloudinaryUploader
import com.undal.syl.app.core.session.AuthSession
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.core.util.Validators
import com.undal.syl.app.data.repo.AuthRepo
import com.undal.syl.app.core.fcm.TokenRegistrar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The customer's own account (C-079 · C-080).
 *
 * Everything the rider and the kitchen rely on lives here — the name they call
 * out, the number they ring, the point they deliver to, and the photo that makes
 * the handover obvious. Changing the password is gated behind the current one,
 * and deleting the account is gated behind a confirmation sheet, because both
 * are irreversible from the app.
 *
 * The address itself is edited on the map screen, not here: a text field cannot
 * produce the coordinates the delivery radius is measured against.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepo: AuthRepo,
    private val uploader: CloudinaryUploader,
    private val registrar: TokenRegistrar,
    private val heartbeat: PresenceHeartbeat,
    private val session: AuthSession,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val name: String = "",
        val email: String = "",
        val phone: String = "",
        val photoUrl: String = "",
        val address: UndalLocation? = null,
        val emailVerified: Boolean = true,
        val savingBasics: Boolean = false,
        val uploading: Boolean = false,
        val changingPassword: Boolean = false,
        val deleting: Boolean = false,
        val resendingVerification: Boolean = false,
        val checkingVerification: Boolean = false,
        /** When the last answer about the email came from the server. */
        val verificationCheckedAtMillis: Long = 0L,
        /** False when the last check could not reach Firebase at all. */
        val verificationReachable: Boolean = true,
        val notice: String? = null,
        val noticeIsError: Boolean = false
    ) {

        val addressSummary: String get() = address?.let { "${it.title} · ${it.desc}" }.orEmpty()

        val addressPinLabel: String get() = address?.let {
            UndalLocation.latLngLabel(it.lat, it.lng)
        }.orEmpty()

        val hasAddress: Boolean get() = address != null

        val displayName: String get() = name.ifBlank { "Undal customer" }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    /**
     * Nothing this ViewModel does may end the process.
     *
     * An exception that escapes a `viewModelScope` job is uncaught, and Android's
     * answer to an uncaught exception is to kill the app — which the person
     * experiences as the app vanishing a few seconds after it opened, then the
     * crash guard reopening it, then the home screen. Every coroutine here carries
     * this handler, so a failed read becomes one sentence where the screen already
     * shows messages.
     */
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("reading your profile") { message ->
        _state.value = _state.value.copy(notice = message, noticeIsError = true)
    }

    init {
        load()
    }

    fun load() {
        viewModelScope.launch(guard) {
            _state.value = _state.value.copy(loading = true)
            val location = authRepo.loadProfileLocation()
            _state.value = State(
                loading = false,
                name = authRepo.loadProfileName().orEmpty(),
                email = authRepo.currentEmail().orEmpty(),
                phone = authRepo.loadProfilePhone().orEmpty(),
                photoUrl = authRepo.loadProfilePhoto().orEmpty(),
                address = location,
                // A server question, not a cached one: see AuthRepo.isEmailVerified.
                emailVerified = authRepo.isEmailVerified(),
                verificationCheckedAtMillis = System.currentTimeMillis(),
                notice = _state.value.notice,
                noticeIsError = _state.value.noticeIsError
            )
        }
    }

    /**
     * Asks Firebase whether this address is confirmed yet (C-009).
     *
     * The customer leaves the app to open their mail, so the app cannot be told
     * the answer — it has to ask. This is the ask, and it is safe to call often:
     * a confirmed account answers from memory, and an unconfirmed one costs one
     * small request.
     *
     * @param quiet true for the automatic checks (returning from the mail app and
     *   the polling window), which must not put a notice on the screen every time
     *   the app resumes; false for the explicit "Check again" tap, which must
     *   always say something.
     */
    fun checkEmailVerification(quiet: Boolean = true) {
        if (_state.value.checkingVerification) return
        if (_state.value.emailVerified) return
        _state.value = _state.value.copy(checkingVerification = true)
        viewModelScope.launch(guard) {
            // `refreshUser` republishes the session, so the app-wide flag — the
            // one the router and every other screen read — moves with this one.
            session.refreshUser()
            val (verified, reached) = authRepo.checkEmailVerifiedWithNetwork()
            val current = _state.value
            _state.value = current.copy(
                checkingVerification = false,
                emailVerified = verified,
                verificationReachable = reached,
                verificationCheckedAtMillis = System.currentTimeMillis(),
                notice = when {
                    verified -> "Thank you — ${current.email.ifBlank { "your address" }} is confirmed."
                    !quiet && !reached -> "We could not reach Firebase just now. Check your connection and tap again."
                    !quiet -> "Not confirmed yet. Open the link in the email we sent to " +
                        "${current.email.ifBlank { "your address" }}, then tap Check again."
                    else -> current.notice
                },
                noticeIsError = !quiet && !reached
            )
            if (verified) verificationWatch?.cancel()
        }
    }

    /**
     * Watches for the confirmation for a bounded two minutes.
     *
     * The window exists because the honest answer to "I tapped the link" is
     * usually a few seconds away, and a customer who has to guess when to press a
     * button assumes the button is broken. It is deliberately bounded: this is a
     * screen that may be left open on a table, and an app that polls Firebase
     * forever is a battery complaint.
     */
    fun watchForVerification() {
        if (_state.value.emailVerified) return
        if (verificationWatch?.isActive == true) return
        verificationWatch = viewModelScope.launch(guard) {
            repeat(VERIFICATION_POLL_ATTEMPTS) {
                delay(VERIFICATION_POLL_MILLIS)
                if (_state.value.emailVerified) return@repeat
                checkEmailVerification(quiet = true)
            }
        }
    }

    private var verificationWatch: kotlinx.coroutines.Job? = null

    fun refreshAddress() {
        viewModelScope.launch(guard) {
            _state.value = _state.value.copy(address = authRepo.loadProfileLocation())
        }
    }

    fun saveBasics(name: String, phone: String) {
        if (_state.value.savingBasics) return
        _state.value = _state.value.copy(savingBasics = true)
        viewModelScope.launch(guard) {
            when (val result = authRepo.saveProfileBasics(name, phone)) {
                is UndalResult.Success -> {
                    val cleanPhone = Validators.normalizeBdMobile(phone).orEmpty()
                    _state.value = _state.value.copy(
                        savingBasics = false,
                        name = name.trim(),
                        phone = cleanPhone.ifBlank { phone.trim() },
                        notice = "Saved. The kitchen and the rider will use this name and number.",
                        noticeIsError = false
                    )
                }

                is UndalResult.Failure -> {
                    _state.value = _state.value.copy(
                        savingBasics = false,
                        notice = result.message,
                        noticeIsError = true
                    )
                }
            }
        }
    }

    /** Uploads the chosen photo to Cloudinary, then stores only its URL (C-079). */
    fun uploadPhoto(uri: Uri) {
        if (_state.value.uploading) return
        _state.value = _state.value.copy(uploading = true, notice = "Uploading your photo…", noticeIsError = false)
        viewModelScope.launch(guard) {
            when (val upload = uploader.uploadImage(uri)) {
                is UndalResult.Success -> {
                    when (val save = authRepo.saveProfilePhoto(upload.value.secureUrl)) {
                        is UndalResult.Success -> {
                            _state.value = _state.value.copy(
                                uploading = false,
                                photoUrl = upload.value.secureUrl,
                                notice = "Photo updated.",
                                noticeIsError = false
                            )
                        }

                        is UndalResult.Failure -> {
                            _state.value = _state.value.copy(
                                uploading = false,
                                notice = save.message,
                                noticeIsError = true
                            )
                        }
                    }
                }

                is UndalResult.Failure -> {
                    _state.value = _state.value.copy(
                        uploading = false,
                        notice = upload.message,
                        noticeIsError = true
                    )
                }
            }
        }
    }

    fun changePassword(current: String, fresh: String, confirmation: String) {
        if (_state.value.changingPassword) return
        if (fresh != confirmation) {
            _state.value = _state.value.copy(
                notice = "The two new passwords do not match.",
                noticeIsError = true
            )
            return
        }
        _state.value = _state.value.copy(changingPassword = true)
        viewModelScope.launch(guard) {
            when (val result = authRepo.changePassword(current, fresh)) {
                is UndalResult.Success -> {
                    _state.value = _state.value.copy(
                        changingPassword = false,
                        notice = "Password changed. Use the new one the next time you sign in.",
                        noticeIsError = false
                    )
                }

                is UndalResult.Failure -> {
                    _state.value = _state.value.copy(
                        changingPassword = false,
                        notice = result.message,
                        noticeIsError = true
                    )
                }
            }
        }
    }

    fun resendVerification() {
        if (_state.value.resendingVerification) return
        _state.value = _state.value.copy(resendingVerification = true)
        viewModelScope.launch(guard) {
            when (val result = authRepo.resendVerificationEmail()) {
                is UndalResult.Success -> {
                    _state.value = _state.value.copy(
                        resendingVerification = false,
                        notice = "A new link is on its way to ${_state.value.email.ifBlank { "your inbox" }}. " +
                            "Open it, then tap Check again — this screen will notice on its own too.",
                        noticeIsError = false
                    )
                    // The link is out; watch for it being opened rather than
                    // making the customer guess when to come back and look.
                    watchForVerification()
                }

                is UndalResult.Failure -> {
                    _state.value = _state.value.copy(
                        resendingVerification = false,
                        notice = result.message,
                        noticeIsError = true
                    )
                }
            }
        }
    }

    /**
     * Signs out and stops every background promise with it: no token left
     * registered, no heartbeat claiming this phone is online, no presence doc
     * left saying the kitchen is staffed.
     */
    fun signOut() {
        viewModelScope.launch(guard) {
            session.currentUser?.uid?.let { uid -> registrar.unregister(uid) }
            PresenceHeartbeat.stopService(context)
            PresenceHeartbeatWorker.cancel(context)
            heartbeat.markOffline()
            // No session, no ringing: whichever screen signs out, the watcher,
            // the alert and the watchdog that revives them go with the account.
            KitchenWatchService.stop(context)
            OrderAlertController.stop()
            session.signOut()
        }
    }

    /** Deletes the account, the profile document and every FCM token (C-080). */
    fun deleteAccount() {
        if (_state.value.deleting) return
        _state.value = _state.value.copy(deleting = true)
        viewModelScope.launch(guard) {
            when (val result = authRepo.deleteAccount()) {
                is UndalResult.Success -> {
                    PresenceHeartbeat.stopService(context)
                    PresenceHeartbeatWorker.cancel(context)
                    KitchenWatchService.stop(context)
                    OrderAlertController.stop()
                    _state.value = _state.value.copy(
                        deleting = false,
                        notice = "Your account has been deleted.",
                        noticeIsError = false
                    )
                }

                is UndalResult.Failure -> {
                    _state.value = _state.value.copy(
                        deleting = false,
                        notice = result.message,
                        noticeIsError = true
                    )
                }
            }
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    override fun onCleared() {
        verificationWatch?.cancel()
        super.onCleared()
    }

    private companion object {
        /** Long enough not to hammer Firebase, short enough to feel instant. */
        const val VERIFICATION_POLL_MILLIS = 5_000L
        const val VERIFICATION_POLL_ATTEMPTS = 24
    }
}
