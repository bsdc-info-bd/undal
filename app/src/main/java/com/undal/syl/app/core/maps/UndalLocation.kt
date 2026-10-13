package com.undal.syl.app.core.maps

import com.google.firebase.firestore.GeoPoint

/**
 * The one address shape in Undal (G1 · PART K).
 *
 * Coordinates are mandatory, and so are all three text fields: a title the
 * customer recognises ("Home"), a description a rider can follow ("Flat 4B,
 * green building beside the mosque") and a note for the knock ("Call when you
 * reach the gate"). The Rider never has to phone for directions, and the
 * customer never has to type an address twice.
 */
data class UndalLocation(
    val lat: Double,
    val lng: Double,
    val title: String = "",
    val desc: String = "",
    val note: String = "",
    val geohashLabel: String = "",
    val accuracyMeters: Float? = null
) {

    val isValid: Boolean
        get() = lat in -90.0..90.0 &&
            lng in -180.0..180.0 &&
            title.trim().length >= MIN_TEXT &&
            desc.trim().length >= MIN_TEXT &&
            note.trim().length >= MIN_TEXT

    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "lat" to lat,
        "lng" to lng,
        "title" to title.trim(),
        "desc" to desc.trim(),
        "note" to note.trim(),
        "geohashLabel" to geohashLabel.trim()
    )

    fun toGeoPoint(): GeoPoint = GeoPoint(lat, lng)

    companion object {
        const val MIN_TEXT = 3

        /** Reads the map shape written by the app or seeded by the Worker. */
        fun fromFirestoreMap(map: Map<*, *>?): UndalLocation? {
            if (map == null) return null
            val lat = (map["lat"] as? Number)?.toDouble() ?: return null
            val lng = (map["lng"] as? Number)?.toDouble() ?: return null
            return UndalLocation(
                lat = lat,
                lng = lng,
                title = (map["title"] as? String).orEmpty(),
                desc = (map["desc"] as? String).orEmpty(),
                note = (map["note"] as? String).orEmpty(),
                geohashLabel = (map["geohashLabel"] as? String).orEmpty()
            )
        }

        fun latLngLabel(lat: Double, lng: Double): String =
            String.format(java.util.Locale.ENGLISH, "%.5f, %.5f", lat, lng)
    }
}
