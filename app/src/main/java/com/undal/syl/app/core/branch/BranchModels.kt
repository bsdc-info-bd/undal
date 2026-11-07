package com.undal.syl.app.core.branch

import com.undal.syl.app.core.maps.UndalLocation
import com.undal.syl.app.core.model.StoreConfig
import org.json.JSONArray
import org.json.JSONObject

/** The server-authoritative answer to which Undal kitchen serves this pin. */
data class BranchSelection(
    val branchId: String,
    val branchName: String,
    val store: StoreConfig,
    val status: String,
    val distanceKm: Double?,
    val deliveryPaisa: Long,
    val nearestBranchName: String = "",
    val nearestDistanceKm: Double? = null,
    val canRequestExtension: Boolean = false,
    val message: String = "",
    val resolvedAtMillis: Long = System.currentTimeMillis(),
    val fromCache: Boolean = false,
    val legacySingleBranch: Boolean = false,
) {
    val deliverable: Boolean get() = status == "deliverable" && branchId.isNotBlank()
    val outsideCoverage: Boolean get() = status == "outside_coverage" || status == "no_branches"

    fun asCached(): BranchSelection = copy(fromCache = true)

    companion object {
        /** Before the first network answer, retain the original single-store behaviour. */
        fun legacy(): BranchSelection = BranchSelection(
            branchId = "default",
            branchName = "Undal HomeKitchen",
            store = StoreConfig.unloaded(),
            status = "deliverable",
            distanceKm = null,
            deliveryPaisa = 0L,
            legacySingleBranch = true,
        )

        fun unavailable(message: String): BranchSelection = BranchSelection(
            branchId = "",
            branchName = "",
            store = StoreConfig.unloaded(),
            status = "unavailable",
            distanceKm = null,
            deliveryPaisa = 0L,
            message = message,
        )

        fun invalid(): BranchSelection = unavailable("Drop a valid delivery pin to find your Undal branch.")
            .copy(status = "invalid_location")

        fun fromWorker(json: JSONObject, fromCache: Boolean = false): BranchSelection {
            val branch = json.optJSONObject("branch")
            val nearest = json.optJSONObject("nearest")
            val nearestBranch = nearest?.optJSONObject("branch")
            val id = json.optString("branchId").ifBlank { branch?.optString("id").orEmpty() }
            val name = json.optString("branchName").ifBlank {
                branch?.optString("name")?.ifBlank { branch.optString("storeName") }.orEmpty()
            }.ifBlank { "Undal HomeKitchen" }
            val store = branch?.let { StoreConfig.fromMap(it.toStringMap()) } ?: StoreConfig.unloaded()
            return BranchSelection(
                branchId = id,
                branchName = name,
                store = store.copy(storeName = name),
                status = json.optString("status").ifBlank { "unavailable" },
                distanceKm = json.optionalDouble("distanceKm"),
                deliveryPaisa = json.optLong("deliveryPaisa"),
                nearestBranchName = nearestBranch?.optString("name").orEmpty(),
                nearestDistanceKm = nearest?.optionalDouble("distanceKm"),
                canRequestExtension = json.optBoolean("canRequestExtension"),
                message = json.optString("message"),
                fromCache = fromCache,
                legacySingleBranch = branch?.optBoolean("legacy") == true,
            )
        }
    }
}

/** Thrown instead of silently mixing dishes priced by two different branches. */
class BranchCartConflictException(val selectedBranchName: String) : IllegalStateException(
    "Your basket belongs to another branch. Clear it before ordering from $selectedBranchName.",
)

internal fun JSONObject.optionalDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

internal fun JSONObject.toStringMap(): Map<String, Any?> = buildMap {
    keys().forEach { key -> put(key, jsonValue(get(key))) }
}

private fun jsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toStringMap()
    is JSONArray -> (0 until value.length()).map { index -> jsonValue(value.get(index)) }
    else -> value
}

/** Location identity used to decide whether a cached branch answer still applies. */
internal fun sameDeliveryArea(cached: UndalLocation?, current: UndalLocation): Boolean {
    if (cached == null) return false
    val latDelta = kotlin.math.abs(cached.lat - current.lat)
    val lngDelta = kotlin.math.abs(cached.lng - current.lng)
    // About 55 m: close enough for a map's tiny centre drift, not another neighbourhood.
    return latDelta <= 0.0005 && lngDelta <= 0.0005
}
