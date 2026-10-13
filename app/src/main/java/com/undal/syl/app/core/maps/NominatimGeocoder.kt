package com.undal.syl.app.core.maps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Human-readable place names, without any paid API.
 *
 * Nominatim's usage policy allows light, identified, one-per-second traffic, so
 * this client:
 *   · sends a unique User-Agent that names the app and the Worker host,
 *   · never issues two requests inside one second,
 *   · gives up quietly and returns null, in which case the UI shows plain
 *     coordinates ("Pinned location (24.89490, 91.86870)") instead of an error.
 *
 * A missing place name must never block a customer from saving an address.
 */
@Singleton
class NominatimGeocoder @Inject constructor(
    private val client: OkHttpClient,
    @Named("nominatimUserAgent") private val userAgent: String
) {

    private val lastRequestAt = AtomicLong(0L)

    /** Reverse geocode → a short label such as "Zindabazar, Sylhet". */
    suspend fun reverse(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        throttle()
        val url = buildString {
            append("https://nominatim.openstreetmap.org/reverse")
            append("?format=json")
            append("&lat=").append(lat)
            append("&lon=").append(lng)
            append("&zoom=17")
            append("&addressdetails=1")
            append("&accept-language=en")
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                val json = JSONObject(body)
                val display = json.optString("display_name").takeIf { it.isNotBlank() }
                val address = json.optJSONObject("address")
                val short = buildString {
                    val road = address?.optString("road").orEmpty()
                    val suburb = address?.optString("suburb")
                        ?.ifBlank { null }
                        ?: address?.optString("neighbourhood").orEmpty()
                    val city = address?.optString("city")
                        ?.ifBlank { null }
                        ?: address?.optString("town").orEmpty()
                    if (road.isNotBlank()) append(road)
                    if (suburb.isNotBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(suburb)
                    }
                    if (city.isNotBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(city)
                    }
                }
                when {
                    short.isNotBlank() -> short
                    display != null -> display.take(120)
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** The label the app falls back to when geocoding is unavailable. */
    fun fallbackLabel(lat: Double, lng: Double): String =
        "Pinned location (${UndalLocation.latLngLabel(lat, lng)})"

    private suspend fun throttle() {
        val now = System.currentTimeMillis()
        val previous = lastRequestAt.get()
        val waitFor = MIN_INTERVAL_MILLIS - (now - previous)
        if (waitFor > 0) {
            kotlinx.coroutines.delay(waitFor)
        }
        lastRequestAt.set(System.currentTimeMillis())
    }

    private companion object {
        /** Nominatim policy: at most one request per second. */
        const val MIN_INTERVAL_MILLIS = 1_100L
    }
}
