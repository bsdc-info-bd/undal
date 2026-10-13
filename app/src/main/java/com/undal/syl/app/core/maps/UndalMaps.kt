package com.undal.syl.app.core.maps

import android.content.Context
import com.undal.syl.app.core.config.UndalBuildInfo
import org.osmdroid.config.Configuration

/**
 * osmdroid global configuration (PART K).
 *
 * Two rules from the OpenStreetMap tile policy are enforced here, once, at
 * application start: an application-specific cache directory and a unique,
 * identifying user agent. Neither is optional — an anonymous bulk tile fetch can
 * get the whole app blocked from the tile servers.
 */
object UndalMaps {

    private const val USER_AGENT_TEMPLATE = "UndalApp/%s (+%s)"

    @Volatile
    private var initialised = false

    fun initialize(context: Context) {
        if (initialised) return
        initialised = true
        val config = Configuration.getInstance()
        config.load(
            context.applicationContext,
            context.getSharedPreferences("undal_osmdroid", Context.MODE_PRIVATE)
        )
        config.userAgentValue = String.format(
            java.util.Locale.ENGLISH,
            USER_AGENT_TEMPLATE,
            UndalBuildInfo.versionName,
            UndalBuildInfo.workerHost
        )
        config.osmdroidBasePath =
            java.io.File(context.cacheDir, "osmdroid")
        config.osmdroidTileCache =
            java.io.File(config.osmdroidBasePath, "tiles")
    }

    /** Attribution text every map surface must show. */
    const val ATTRIBUTION = "© OpenStreetMap contributors"

    /** Zoom levels used across the app. */
    const val ZOOM_CITY = 12.0
    const val ZOOM_STREET = 16.0
    const val ZOOM_HOUSE = 18.0

    /** The Google embed used for display-only views (no API key, ever). */
    fun googleEmbedUrl(lat: Double, lng: Double, zoom: Int = 17): String =
        "https://www.google.com/maps?q=$lat,$lng&z=$zoom&output=embed"
}
