package com.undal.syl.app.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.undal.syl.app.core.branch.BranchSelection
import com.undal.syl.app.core.branch.sameDeliveryArea
import com.undal.syl.app.core.maps.UndalLocation
import com.undal.syl.app.core.net.WorkerApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One process-wide branch selection shared by the storefront, basket, item page
 * and checkout. The Worker's polygon/radius resolver is authoritative; Firestore
 * then supplies live hours, availability and prices for that selected branch.
 */
@Singleton
class BranchRepo @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val worker: WorkerApi,
) {
    private val live = MutableStateFlow<BranchSelection?>(null)

    private val cached: Flow<Pair<BranchSelection, UndalLocation?>> = dataStore.data.combine(
        // A tiny constant flow keeps combine's two-input shape readable.
        kotlinx.coroutines.flow.flowOf(Unit),
    ) { preferences, _ ->
        val selection = preferences[KEY_SELECTION]
            ?.let { raw -> runCatching { BranchSelection.fromWorker(JSONObject(raw), fromCache = true) }.getOrNull() }
            ?: BranchSelection.legacy()
        val lat = preferences[KEY_LAT]
        val lng = preferences[KEY_LNG]
        val location = if (lat != null && lng != null) {
            UndalLocation(title = "", desc = "", note = "", lat = lat, lng = lng)
        } else null
        selection to location
    }

    val selection: Flow<BranchSelection> = combine(live, cached) { current, stored ->
        current ?: stored.first
    }

    suspend fun current(): BranchSelection = selection.first()

    /** Resolve and atomically publish the branch that all customer repositories observe. */
    suspend fun resolve(location: UndalLocation?): BranchSelection {
        if (location == null || !location.lat.isFinite() || !location.lng.isFinite() ||
            location.lat !in -90.0..90.0 || location.lng !in -180.0..180.0 ||
            (location.lat == 0.0 && location.lng == 0.0)
        ) {
            return BranchSelection.invalid().also { live.value = it }
        }
        val stored = cached.first()
        worker.resolveBranch(location.lat, location.lng).fold(
            onSuccess = { answer ->
                val resolved = BranchSelection.fromWorker(answer)
                live.value = resolved
                if (resolved.deliverable || resolved.outsideCoverage) {
                    dataStore.edit { preferences ->
                        preferences[KEY_SELECTION] = answer.toString()
                        preferences[KEY_LAT] = location.lat
                        preferences[KEY_LNG] = location.lng
                    }
                }
                return resolved
            },
            onFailure = { error ->
                if (stored.first.deliverable && sameDeliveryArea(stored.second, location)) {
                    return stored.first.asCached().copy(
                        message = "Using your last confirmed branch while the network reconnects.",
                    ).also { live.value = it }
                }
                return BranchSelection.unavailable(
                    error.message ?: "We could not find your branch. Check the connection and try again.",
                ).also { live.value = it }
            },
        )
    }

    /** A request creates no order and never widens coverage by itself. */
    suspend fun requestExtension(location: UndalLocation): Result<JSONObject> {
        val payload = JSONObject()
            .put("lat", location.lat)
            .put("lng", location.lng)
            .put("addressTitle", location.title)
            .put("addressDesc", location.desc)
            .put("addressNote", location.note)
            .put("source", "android-customer")
        return worker.requestDeliveryExtension(payload)
    }

    private companion object {
        val KEY_SELECTION = stringPreferencesKey("branch_resolution_json")
        val KEY_LAT = doublePreferencesKey("branch_resolution_lat")
        val KEY_LNG = doublePreferencesKey("branch_resolution_lng")
    }
}
