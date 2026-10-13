package com.undal.syl.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.undal.syl.app.core.maps.UndalLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small, device-local state that must survive a process death:
 *   · whether the opening walk-through has been seen,
 *   · the draft address while a customer is pinning it (so a phone call in the
 *     middle of the location step does not lose their work),
 *   · the kitchen's acknowledgement of the diagnostics wizard.
 *
 * None of this is authoritative business data — that all lives in Firestore —
 * which is exactly why it belongs in DataStore rather than the database.
 */
@Singleton
class OnboardingProgress @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    data class DraftAddress(
        val lat: Double,
        val lng: Double,
        val title: String,
        val desc: String,
        val note: String,
        val label: String
    ) {
        fun toLocation(): UndalLocation = UndalLocation(
            lat = lat,
            lng = lng,
            title = title,
            desc = desc,
            note = note,
            geohashLabel = label
        )
    }

    val welcomeSeen: Flow<Boolean> = dataStore.data.map { it[KEY_WELCOME_SEEN] ?: false }

    val diagnosticsDone: Flow<Boolean> = dataStore.data.map { it[KEY_DIAGNOSTICS_DONE] ?: false }

    val draftAddress: Flow<DraftAddress?> = dataStore.data.map { preferences ->
        val lat = preferences[KEY_DRAFT_LAT] ?: return@map null
        val lng = preferences[KEY_DRAFT_LNG] ?: return@map null
        DraftAddress(
            lat = lat,
            lng = lng,
            title = preferences[KEY_DRAFT_TITLE].orEmpty(),
            desc = preferences[KEY_DRAFT_DESC].orEmpty(),
            note = preferences[KEY_DRAFT_NOTE].orEmpty(),
            label = preferences[KEY_DRAFT_LABEL].orEmpty()
        )
    }

    suspend fun markWelcomeSeen() {
        dataStore.edit { it[KEY_WELCOME_SEEN] = true }
    }

    suspend fun markDiagnosticsDone() {
        dataStore.edit { it[KEY_DIAGNOSTICS_DONE] = true }
    }

    suspend fun saveDraftAddress(draft: DraftAddress) {
        dataStore.edit { preferences ->
            preferences[KEY_DRAFT_LAT] = draft.lat
            preferences[KEY_DRAFT_LNG] = draft.lng
            preferences[KEY_DRAFT_TITLE] = draft.title
            preferences[KEY_DRAFT_DESC] = draft.desc
            preferences[KEY_DRAFT_NOTE] = draft.note
            preferences[KEY_DRAFT_LABEL] = draft.label
        }
    }

    suspend fun clearDraftAddress() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_DRAFT_LAT)
            preferences.remove(KEY_DRAFT_LNG)
            preferences.remove(KEY_DRAFT_TITLE)
            preferences.remove(KEY_DRAFT_DESC)
            preferences.remove(KEY_DRAFT_NOTE)
            preferences.remove(KEY_DRAFT_LABEL)
        }
    }

    data class DraftProfile(val name: String, val email: String, val phone: String)

    val draftProfile: Flow<DraftProfile?> = dataStore.data.map { preferences ->
        val name = preferences[KEY_PROFILE_NAME] ?: return@map null
        DraftProfile(
            name = name,
            email = preferences[KEY_PROFILE_EMAIL].orEmpty(),
            phone = preferences[KEY_PROFILE_PHONE].orEmpty()
        )
    }

    suspend fun saveDraftProfile(profile: DraftProfile) {
        dataStore.edit { preferences ->
            preferences[KEY_PROFILE_NAME] = profile.name
            preferences[KEY_PROFILE_EMAIL] = profile.email
            preferences[KEY_PROFILE_PHONE] = profile.phone
        }
    }

    suspend fun clearDraftProfile() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_PROFILE_NAME)
            preferences.remove(KEY_PROFILE_EMAIL)
            preferences.remove(KEY_PROFILE_PHONE)
        }
    }

    /** Wipes every device-local trace of the previous session. */
    suspend fun clearSessionState() {
        clearDraftAddress()
        clearDraftProfile()
    }


    companion object {
        private val KEY_WELCOME_SEEN = booleanPreferencesKey("welcome_seen")
        private val KEY_DIAGNOSTICS_DONE = booleanPreferencesKey("diagnostics_done")
        private val KEY_DRAFT_LAT = androidx.datastore.preferences.core.doublePreferencesKey("draft_lat")
        private val KEY_DRAFT_LNG = androidx.datastore.preferences.core.doublePreferencesKey("draft_lng")
        private val KEY_DRAFT_TITLE = stringPreferencesKey("draft_title")
        private val KEY_DRAFT_DESC = stringPreferencesKey("draft_desc")
        private val KEY_DRAFT_NOTE = stringPreferencesKey("draft_note")
        private val KEY_DRAFT_LABEL = stringPreferencesKey("draft_label")
        private val KEY_PROFILE_NAME = stringPreferencesKey("profile_name")
        private val KEY_PROFILE_EMAIL = stringPreferencesKey("profile_email")
        private val KEY_PROFILE_PHONE = stringPreferencesKey("profile_phone")
    }
}
