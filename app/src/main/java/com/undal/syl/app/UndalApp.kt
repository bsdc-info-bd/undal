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
import com.undal.syl.app.core.background.AlertWatchdog
import com.undal.syl.app.core.background.PresenceHeartbeatWorker
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.maps.UndalMaps
import com.undal.syl.app.core.session.AuthSession
import com.undal.syl.app.core.session.VendorSessionKeeper
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.telemetry.UndalTelemetry
import com.undal.syl.app.core.util.UndalCrashGuard
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Two rules govern everything in `onCreate`, and both were learned the hard way.
 *
 * **Nothing here may end the process.** `Application.onCreate` runs before there
 * is a screen, so an exception thrown here is an app that will not open at all —
 * and the crash guard then reopens it, into the same exception, until its budget
 * is gone. Every step below is therefore its own `runCatching` with its own name
 * in the log, so one SDK that refuses to initialise costs that SDK and nothing
 * else. A phone with no Play Services, no network and a half-written
 * `google-services.json` still opens Undal.
 *
 * **Nothing here may cost the opening frame.** This method runs on the main
 * thread, and the main thread is what Android measures "isn't responding"
 * against. WorkManager opens a database the first time it is touched, osmdroid
 * creates its tile cache on disk, Cloudinary reads its configuration back, an
 * alarm has to be handed to the system and a preference file has to be written —
 * all of which used to happen here, one after another, before the first screen
 * was even composed. Only two things genuinely have to run first, and both are
 * *configuration* rather than work: Firestore's cache policy has to be set before
 * the first query, and Realtime Database persistence has to be switched on before
 * the first reference. Everything else is dispatched and the app opens without it.
 *
 * Responsibilities that must happen exactly once per process:
 *  - the crash guard, before anything that could crash
 *  - Firestore offline persistence, 100 MB local cache (PART P-021)
 *  - Realtime Database disk persistence (PART P-022)
 *  - Cloudinary unsigned-upload configuration (PART H)
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

    @Inject
    lateinit var telemetry: UndalTelemetry

    /**
     * Process-scoped coroutine scope for work that must outlive any screen — the
     * kitchen session watchdog lives here (V-002). It is cancelled by the
     * operating system when the process dies, which is exactly the intended
     * lifetime: the watchdog re-starts with the process.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Where the opening work that is not allowed on the main thread goes. `IO`
     * rather than `Default`, because every step here is a file, a database or a
     * binder call, and a `SupervisorJob` so that one of them failing cannot
     * cancel the rest.
     */
    private val startScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Installed first, and before Crashlytics can install its own handler, so
        // that ours keeps a reference to it and can hand over to it deliberately.
        // A crash in the rest of this method is still a crash the owner can read
        // instead of a phone that silently will not open.
        firstStep("crash guard") { UndalCrashGuard.install(this) }

        // Crash reporting and the cold-start trace start before anything else can
        // be slow: a performance trace that misses the first 300 ms is theatre.
        firstStep("telemetry") {
            telemetry.configureForBuild(BuildConfig.DEBUG)
            telemetry.beginAppStart()
        }

        // The two that have to be configuration rather than work — see the class
        // comment. Each is separate, so a Firestore that will not open its cache
        // does not also take the database's persistence down with it.
        firstStep("firestore persistence") { configureFirestorePersistence() }
        firstStep("realtime database") { configureRealtimeDatabase() }

        // Everything below can wait a few milliseconds, and none of it may cost
        // the opening frame.
        startScope.launch {
            deferredStep("cloudinary") { configureCloudinary() }
            // osmdroid needs its cache directory and its identifying user agent set
            // before the first tile request, or the OSM tile policy is violated.
            // The first tile request is at least two taps away from a cold start.
            deferredStep("maps") { UndalMaps.initialize(this@UndalApp) }
            // Channels are created on every start: a ROM update can silently drop
            // them, and a missing channel means a silent order alert. MainActivity
            // does this again synchronously, which is the belt to these braces.
            deferredStep("notification channels") { AlarmChannels.ensureChannels(this@UndalApp) }
            // The heartbeat has to outlive this process two ways: the WorkManager
            // job (fifteen minutes, the floor WorkManager allows) across a reboot,
            // and the alert watchdog (nine minutes) across Android killing the
            // app. Both are kitchen-only, because a customer phone that beat the
            // kitchen's heartbeat would hold a closed store open, and a customer
            // phone that armed a watchdog would be a battery complaint with no
            // upside at all.
            deferredStep("kitchen background") { armKitchenIfThisIsOne() }
            // A kitchen phone must never be signed out by surprise: the watchdog
            // refreshes the token and re-checks the vendor claim every 20 minutes
            // for as long as the process lives (V-002).
            deferredStep("session keeper") {
                if (session.isSignedIn()) sessionKeeper.start(appScope)
            }
            // A crash report without the account it happened to is a puzzle, not a bug.
            deferredStep("crash identity") { session.uid?.let { uid -> telemetry.identify(uid) } }
        }
    }

    /**
     * The kitchen's two self-healing mechanisms, armed only on a kitchen phone.
     *
     * [AlertWatchdog.isKitchenDevice] reads the role the router last established
     * for the account that is signed in, synchronously, because this runs in a
     * process that may have been started by an alarm and has no time to ask the
     * server anything.
     */
    private fun armKitchenIfThisIsOne() {
        if (!AlertWatchdog.isKitchenDevice(this)) return
        PresenceHeartbeatWorker.ensureScheduled(this)
        AlertWatchdog.arm(this)
    }

    /** A step whose failure must cost the step and nothing else. */
    private fun firstStep(name: String, step: () -> Unit) {
        try {
            step()
        } catch (error: Throwable) {
            Log.e(TAG, "Undal opened without $name", error)
        }
    }

    /** The same, for a step that is already off the main thread. */
    private fun deferredStep(name: String, step: () -> Unit) {
        runCatching { step() }
            .onFailure { error -> Log.e(TAG, "Undal opened without $name", error) }
    }

    /**
     * Cloudinary is initialised with the *cloud name only*: mobile apps must never
     * hold the account secret. Uploads go through the unsigned preset named in
     * the CLOUDINARY_UPLOAD_PRESET secret, which the Cloudinary console restricts
     * to image formats.
     */
    private fun configureCloudinary() {
        val config: Map<String, Any> = mapOf(
            "cloud_name" to UndalBuildInfo.cloudinaryCloudName,
            "secure" to true,
            "unsigned_upload" to true,
            "upload_preset" to UndalBuildInfo.cloudinaryUploadPreset,
        )
        // `init` throws if it is called twice in one process, which is what a
        // process kept alive by a service and re-created by an activity looks like
        // from here. A second configuration is not an error worth a dead app.
        runCatching { MediaManager.init(this, config) }
            .onFailure { error -> Log.w(TAG, "Cloudinary was not reconfigured", error) }
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

    /**
     * Chat and live order flags must still be readable with no network.
     *
     * Persistence is switched on, and that is all. This method used to follow it
     * with `keepSynced(true)` on the whole `chats` root and the whole `orderLive`
     * root, which asks the Realtime Database to download and hold *every message
     * of every order this kitchen has ever had* — on the main thread, at every
     * cold start, growing forever. It is the single most expensive thing this app
     * did before drawing its first frame, and it bought nothing: `keepSynced`
     * pre-warms a node for a listener that has not attached yet, and both of
     * those nodes are read by listeners that attach themselves when a
     * conversation or an order is opened. The small, hot node a chat screen
     * actually needs is kept synced by the chat screen.
     */
    private fun configureRealtimeDatabase() {
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)
    }

    private companion object {
        const val TAG = "UndalApp"
        const val FIRESTORE_CACHE_BYTES: Long = 100L * 1024L * 1024L
    }
}
