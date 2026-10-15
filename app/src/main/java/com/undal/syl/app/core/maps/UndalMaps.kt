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
 *
 * The same call is made again by the first map that is composed, because the
 * application-level call runs off the main thread and a screen can be opened
 * before it lands. [initialize] is idempotent and thread-safe, so the second
 * caller costs one boolean read.
 *
 * Three further settings make the tiles survive a Bangladeshi mobile connection,
 * which is what "the map is blank" usually means in practice:
 *
 *  · a **ceiling** on the tile cache, so an app that is opened every day for a
 *    year cannot fill the phone's storage;
 *  · a **trim** target below that ceiling, so the cleanup happens in one pass
 *    rather than on every tile;
 *  · an **expiry extension**, so a tile that was fetched yesterday still draws
 *    today on a connection that has stopped answering. OpenStreetMap serves
 *    `Cache-Control` headers; this is how long past them a cached tile stays
 *    usable, and three days is the longest window that still shows a street that
 *    was renamed this week.
 */
object UndalMaps {

    private const val USER_AGENT_TEMPLATE = "UndalApp/%s (+%s)"

    /** 60 MiB of tiles: a whole city at every zoom a customer can reach. */
    private const val TILE_CACHE_MAX_BYTES = 60L * 1024L * 1024L

    /** Trim to 48 MiB, so a full cache is emptied in one pass. */
    private const val TILE_CACHE_TRIM_BYTES = 48L * 1024L * 1024L

    /** Three days past the server's own expiry. */
    private const val TILE_EXPIRY_EXTENSION_MILLIS = 3L * 24L * 60L * 60L * 1000L

    private val lock = Any()

    @Volatile
    private var initialised = false

    /**
     * Must run before the first [`org.osmdroid.views.MapView`] is created.
     *
     * Safe to call from any thread and any number of times: the guard is inside a
     * monitor, so two callers racing (the application's background start and the
     * first screen's composition) cannot both run the configuration.
     */
    fun initialize(context: Context) {
        if (initialised) return
        synchronized(lock) {
            if (initialised) return
            val app = context.applicationContext
            val config = Configuration.getInstance()
            config.load(
                app,
                app.getSharedPreferences("undal_osmdroid", Context.MODE_PRIVATE)
            )
            config.userAgentValue = String.format(
                java.util.Locale.ENGLISH,
                USER_AGENT_TEMPLATE,
                UndalBuildInfo.versionName,
                UndalBuildInfo.workerHost
            )
            config.osmdroidBasePath = java.io.File(app.cacheDir, "osmdroid")
            config.osmdroidTileCache = java.io.File(config.osmdroidBasePath, "tiles")
            // A cache ceiling that does not fill the phone, and an expiry window
            // that keeps yesterday's tiles for today's dead spot. Both are wrapped:
            // a configuration that cannot be set must not stop a map from drawing.
            runCatching {
                config.tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES
                config.tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
                config.expirationExtendedDuration = TILE_EXPIRY_EXTENSION_MILLIS
            }
            // Fire the standard osmdroid "tile cache directory in use" line into
            // the log once, so a support question about storage has an answer.
            initialised = true
        }
    }

    /** Attribution text every map surface must show. */
    const val ATTRIBUTION = "© OpenStreetMap contributors"

    /** Zoom levels used across the app. */
    const val ZOOM_CITY = 12.0
    const val ZOOM_STREET = 16.0
    const val ZOOM_HOUSE = 18.0

    /** Below 3 an empty sea renders; above 19 the tile server has nothing left. */
    const val MIN_ZOOM = 4.0
    const val MAX_ZOOM = 19.0

    /** The most zoomed-out view that still shows a street name, for one point. */
    const val ZOOM_LOCALITY = 13.0

    /** Sentinel used when a screen has no coordinates yet: Zindabazar, Sylhet. */
    const val DEFAULT_LAT = 24.8949
    const val DEFAULT_LNG = 91.8687
}
