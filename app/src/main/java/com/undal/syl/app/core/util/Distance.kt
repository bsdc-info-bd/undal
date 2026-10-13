package com.undal.syl.app.core.util

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance, used for the delivery radius gate and the km-based fee.
 *
 * Haversine is accurate to well under a metre at Sylhet-scale distances, which
 * is far beyond what a delivery boundary needs. The result is in kilometres.
 */
object Distance {

    private const val EARTH_RADIUS_KM = 6371.0088

    fun haversineKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * asin(sqrt(a).coerceIn(0.0, 1.0))
        return EARTH_RADIUS_KM * c
    }

    /**
     * The gate the checkout uses. Returns null when the store pin is unknown,
     * so the caller can explain "our kitchen location is not published yet"
     * instead of pretending the customer is out of range.
     *
     * Zero on either axis counts as unknown: the unset marker the seed document
     * writes is 0,0, and no kitchen in Sylhet sits on the equator or the
     * Greenwich meridian — so a half-published pin is refused rather than
     * measured from the wrong point. The web checkout applies the same rule.
     */
    fun distanceFromStore(
        storeLat: Double?,
        storeLng: Double?,
        customerLat: Double,
        customerLng: Double
    ): Double? {
        if (storeLat == null || storeLng == null) return null
        if (storeLat == 0.0 || storeLng == 0.0) return null
        return haversineKm(storeLat, storeLng, customerLat, customerLng)
    }

    fun isWithinRadius(distanceKm: Double, radiusKm: Double): Boolean =
        radiusKm > 0.0 && distanceKm <= radiusKm

    fun formatKm(distanceKm: Double): String {
        val rounded = kotlin.math.round(distanceKm * 10) / 10
        return if (rounded == rounded.toInt().toDouble()) {
            "${rounded.toInt()} km"
        } else {
            String.format(java.util.Locale.ENGLISH, "%.1f km", rounded)
        }
    }
}
