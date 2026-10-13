package com.undal.syl.app.features.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.undal.syl.app.core.maps.UndalLocation
import com.undal.syl.app.core.maps.UndalLocationController
import com.undal.syl.app.core.session.OnboardingProgress
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.core.util.Validators
import com.undal.syl.app.data.repo.AuthRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The mandatory location step (C-010 … C-019).
 *
 * A customer cannot enter the store without a complete address: coordinates plus
 * a title, a description and a delivery note. The screen walks through
 * permission → GPS fix → drag the pin → live place label → confirm on the map →
 * the three required text fields, and it saves a draft after every change, so a
 * phone call in the middle of pinning does not lose the work.
 */
@HiltViewModel
class LocationSetupViewModel @Inject constructor(
    private val controller: UndalLocationController,
    private val authRepo: AuthRepo,
    private val auth: FirebaseAuth,
    private val progress: OnboardingProgress
) : ViewModel() {

    data class State(
        val lat: Double = DEFAULT_LAT,
        val lng: Double = DEFAULT_LNG,
        val accuracyMeters: Float? = null,
        val accuracyNote: String = "",
        val label: String = "",
        val title: String = "",
        val desc: String = "",
        val note: String = "",
        val titleError: String? = null,
        val descError: String? = null,
        val noteError: String? = null,
        val phone: String = "",
        val phoneError: String? = null,
        val busy: Boolean = false,
        val locating: Boolean = false,
        val permissionNeeded: Boolean = false,
        val saved: Boolean = false,
        val banner: String? = null,
        val showOsmFallback: Boolean = false
    ) {
        val location: UndalLocation
            get() = UndalLocation(lat, lng, title, desc, note, label, accuracyMeters)

        companion object {
            /** Zindabazar, Sylhet — the picker opens here before a GPS fix arrives. */
            const val DEFAULT_LAT = 24.8949
            const val DEFAULT_LNG = 91.8687
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var displayName: String = ""

    init {
        viewModelScope.launch {
            // Restore anything already known: a saved address (re-pin flow), a
            // draft from an interrupted session, and the name/phone typed at
            // registration.
            val draftProfile = progress.draftProfile.first()
            displayName = draftProfile?.name ?: authRepo.loadProfileName().orEmpty()

            // The rider has to be able to call. The number typed at registration
            // is the usual source; an address re-pin falls back to the number
            // already saved on the profile. If neither exists the customer types
            // it in the field below — an empty number is refused by the security
            // rules, and a walk to the door should not be what discovers that.
            val knownPhone = draftProfile?.phone?.takeIf { it.isNotBlank() }
                ?: authRepo.loadProfilePhone().orEmpty()
            if (knownPhone.isNotBlank()) {
                _state.value = _state.value.copy(phone = knownPhone)
            }

            val draft = progress.draftAddress.first()
            val saved = if (draft == null) authRepo.loadProfileLocation() else null

            // A draft is a different type from a saved address, so the draft is
            // normalised into an UndalLocation before anything is read off it.
            val restored: UndalLocation? = draft?.toLocation() ?: saved
            if (restored != null) {
                _state.value = _state.value.copy(
                    lat = restored.lat,
                    lng = restored.lng,
                    title = restored.title,
                    desc = restored.desc,
                    note = restored.note,
                    label = restored.geohashLabel
                )
            }
        }
    }

    /**
     * The screen calls this from its permission callback. On a grant it also
     * calls [captureCurrentLocation] with the context it already holds, so there
     * is nothing to start here; on a refusal the flag makes the next use-my-location
     * tap open the rationale instead of a silent prompt.
     */
    fun onPermissionResult(granted: Boolean) {
        if (granted) return
        _state.value = _state.value.copy(
            permissionNeeded = true,
            banner = "Without location permission you can still drag the pin by hand — the map below works either way."
        )
    }

    fun requestPermission() {
        _state.value = _state.value.copy(permissionNeeded = true)
    }

    fun captureCurrentLocation(context: Context) {
        viewModelScope.launch {
            _state.value = _state.value.copy(locating = true, banner = null)
            when (val fix = controller.currentFix(context)) {
                is UndalResult.Success -> {
                    val label = controller.describe(fix.value.lat, fix.value.lng)
                    _state.value = _state.value.copy(
                        lat = fix.value.lat,
                        lng = fix.value.lng,
                        accuracyMeters = fix.value.accuracyMeters,
                        accuracyNote = controller.describeAccuracy(fix.value.accuracyMeters),
                        label = label,
                        locating = false,
                        permissionNeeded = false
                    )
                    persistDraft()
                }

                is UndalResult.Failure -> {
                    _state.value = _state.value.copy(
                        locating = false,
                        banner = fix.message
                    )
                }
            }
        }
    }

    /** Called on every marker drag: new coordinates, then a fresh place label. */
    fun onPinMoved(lat: Double, lng: Double) {
        if (lat == _state.value.lat && lng == _state.value.lng) return
        _state.value = _state.value.copy(
            lat = lat,
            lng = lng,
            accuracyMeters = null,
            accuracyNote = "Pin placed by hand — drag it onto your building.",
            label = controller.geocoderFallback(lat, lng)
        )
        viewModelScope.launch {
            val label = controller.describe(lat, lng)
            _state.value = _state.value.copy(label = label)
            persistDraft()
        }
    }

    fun onTitleChange(value: String) {
        _state.value = _state.value.copy(title = value, titleError = null, banner = null)
    }

    fun onDescChange(value: String) {
        _state.value = _state.value.copy(desc = value, descError = null, banner = null)
    }

    fun onNoteChange(value: String) {
        _state.value = _state.value.copy(note = value, noteError = null, banner = null)
    }

    /** Digits only, eleven of them: what the rules store as 01XXXXXXXXX. */
    fun onPhoneChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(11)
        _state.value = _state.value.copy(phone = digits, phoneError = null, banner = null)
    }

    fun toggleOsmFallback() {
        _state.value = _state.value.copy(showOsmFallback = !_state.value.showOsmFallback)
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        val titleError = Validators.addressTitle(current.title)
        val descError = Validators.addressDescription(current.desc)
        val noteError = Validators.deliveryNote(current.note)
        val phoneError = Validators.bdMobile(current.phone)
        if (titleError != null || descError != null || noteError != null || phoneError != null) {
            _state.value = current.copy(
                titleError = titleError,
                descError = descError,
                noteError = noteError,
                phoneError = phoneError
            )
            return
        }

        _state.value = current.copy(busy = true, banner = null)
        viewModelScope.launch {
            val name = displayName.ifBlank { auth.currentUser?.displayName.orEmpty() }
            // Keep the number on the device as well, so an interrupted save (a
            // call, a flat battery) does not lose it.
            progress.saveDraftProfile(
                OnboardingProgress.DraftProfile(
                    name = name,
                    email = auth.currentUser?.email.orEmpty(),
                    phone = current.phone
                )
            )
            when (val result = authRepo.saveCustomerProfile(name, current.phone, current.location)) {
                is UndalResult.Success -> {
                    progress.clearDraftAddress()
                    _state.value = _state.value.copy(busy = false, saved = true)
                    onSaved()
                }

                is UndalResult.Failure -> {
                    _state.value = _state.value.copy(busy = false, banner = result.message)
                }
            }
        }
    }

    private suspend fun persistDraft() {
        val current = _state.value
        progress.saveDraftAddress(
            OnboardingProgress.DraftAddress(
                lat = current.lat,
                lng = current.lng,
                title = current.title,
                desc = current.desc,
                note = current.note,
                label = current.label
            )
        )
    }
}
