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
import com.undal.syl.app.core.config.UndalBuildInfo
import dagger.hilt.android.HiltAndroidApp
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
