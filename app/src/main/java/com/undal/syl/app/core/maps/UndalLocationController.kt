package com.undal.syl.app.core.maps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.undal.syl.app.core.util.UndalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Getting a trustworthy GPS fix for a Sylhet address (PART K-1 · C-010 … C-014).
 *
 * The flow the UI follows:
 *   1. ask for permission with a written explanation first,
 *   2. request a high-accuracy fix and tell the user what the accuracy is,
 *   3. drop that point into the osmdroid picker for fine adjustment,
 *   4. reverse geocode it for a readable label, falling back to coordinates,
 *   5. confirm the final point on the Google embed before saving.
 *
 * Accuracy is returned rather than hidden: "accurate to 12 m" tells the customer
 * whether to drag the pin, which a silent map does not.
 */
@Singleton
class UndalLocationController @Inject constructor(
    private val geocoder: NominatimGeocoder
) {

    data class Fix(
        val lat: Double,
        val lng: Double,
        val accuracyMeters: Float
    )

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /** One high-accuracy fix. Returns a sentence, never an exception. */
    suspend fun currentFix(context: Context): UndalResult<Fix> {
        if (!hasLocationPermission(context)) {
            return UndalResult.Failure("Undal needs your location permission to pin your address.")
        }
        return runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val location = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                ?: return UndalResult.Failure(
                    "We could not get a GPS fix yet. Move near a window or outside, then try again."
                )
            UndalResult.Success(
                Fix(
                    lat = location.latitude,
                    lng = location.longitude,
                    accuracyMeters = location.accuracy
                )
            )
        }.getOrElse { error ->
            UndalResult.Failure(
                "We could not read your location: ${error.message ?: "the GPS did not answer"}. " +
                    "You can still move the pin by hand."
            )
        }
    }

    /** Readable label for a picked point, with the coordinate fallback baked in. */
    suspend fun describe(lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        geocoder.reverse(lat, lng) ?: geocoder.fallbackLabel(lat, lng)
    }

    /** The coordinate label shown the instant a pin moves, before geocoding returns. */
    fun geocoderFallback(lat: Double, lng: Double): String = geocoder.fallbackLabel(lat, lng)

    /** Honest accuracy wording for the UI. */
    fun describeAccuracy(accuracyMeters: Float?): String {
        if (accuracyMeters == null || accuracyMeters <= 0f) return "Location accuracy unknown"
        val metres = accuracyMeters.toInt()
        return when {
            metres <= 15 -> "Accurate to about $metres m — looks good."
            metres <= 60 -> "Accurate to about $metres m — drag the pin to fine-tune."
            else -> "Only accurate to about $metres m — please drag the pin onto your building."
        }
    }
}
