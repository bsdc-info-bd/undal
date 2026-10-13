package com.undal.syl.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cloudinary.android.MediaManager
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.undal.syl.app.core.background.PresenceHeartbeatWorker
import com.undal.syl.app.core.session.AuthSession
import com.undal.syl.app.core.session.VendorSessionKeeper
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.maps.UndalMaps
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Responsibilities, all of which must happen exactly once per process before
 * any screen or service touches them:
 *  - Cloudinary unsigned-upload configuration (PART H)
 *  - Firestore offline persistence, 100 MB local cache (PART P-021)
 *  - Realtime Database disk persistence + keepSynced for chat and live flags (PART P-022)
 *  - WorkManager wired to Hilt so background workers can inject repositories
 */
@HiltAndroidApp
class UndalApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sessionKeeper: VendorSessionKeeper

    @Inject
    lateinit var session: AuthSession

    /**
     * Process-scoped coroutine scope for work that must outlive any screen — the
     * kitchen session watchdog lives here (V-002). It is cancelled by the
     * operating system when the process dies, which is exactly the intended
     * lifetime: the watchdog re-starts with the process.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        configureCloudinary()
        configureFirestorePersistence()
        configureRealtimeDatabase()
        // osmdroid needs its cache directory and its identifying user agent set
        // before the first tile request, or the OSM tile policy is violated.
        UndalMaps.initialize(this)
        // Channels are created on every start: a ROM update can silently drop
        // them, and a missing channel means a silent order alert.
        AlarmChannels.ensureChannels(this)
        // The kitchen's fifteen-minute heartbeat fallback must survive process
        // death, so it is ensured at every start rather than only after login.
        PresenceHeartbeatWorker.ensureScheduled(this)
        // A kitchen phone must never be signed out by surprise: the watchdog
        // refreshes the token and re-checks the vendor claim every 20 minutes
        // for as long as the process lives (V-002).
        if (session.isSignedIn()) {
            sessionKeeper.start(appScope)
        }
    }

    /**
     * Cloudinary is initialised with the *cloud name only*: mobile apps must never
     * hold the account secret. Uploads go through the unsigned preset
     * `undal_unsigned`, which the Cloudinary console restricts to image formats.
     */
    private fun configureCloudinary() {
        val config: Map<String, Any> = mapOf(
            "cloud_name" to UndalBuildInfo.cloudinaryCloudName,
            "secure" to true,
            "unsigned_upload" to true,
            "upload_preset" to UndalBuildInfo.cloudinaryUploadPreset,
        )
        MediaManager.init(this, config)
    }

    /** Firestore keeps working with no network; the cache is sized for a full menu. */
    private fun configureFirestorePersistence() {
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(
                PersistentCacheSettings.newBuilder()
                    .setSizeBytes(FIRESTORE_CACHE_BYTES)
                    .build()
            )
            .build()
        FirebaseFirestore.getInstance().firestoreSettings = settings
    }

    /** Chat and live order flags must be readable the instant the app opens. */
    private fun configureRealtimeDatabase() {
        val database = FirebaseDatabase.getInstance()
        database.setPersistenceEnabled(true)
        database.getReference(REALTIME_CHATS_ROOT).keepSynced(true)
        database.getReference(REALTIME_ORDER_LIVE_ROOT).keepSynced(true)
    }

    private companion object {
        const val FIRESTORE_CACHE_BYTES: Long = 100L * 1024L * 1024L
        const val REALTIME_CHATS_ROOT = "chats"
        const val REALTIME_ORDER_LIVE_ROOT = "orderLive"
    }
}
