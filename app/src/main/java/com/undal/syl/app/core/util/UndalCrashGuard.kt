package com.undal.syl.app.core.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.undal.syl.app.MainActivity
import com.undal.syl.app.SafeModeActivity
import com.undal.syl.app.core.config.UndalBuildInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.system.exitProcess

/**
 * The last line of defence: an app that vanishes is an app that failed.
 *
 * Every crash this guard sees is reported to Crashlytics, so nothing is hidden
 * from the person who fixes it. What the guard adds is what the customer and the
 * kitchen see instead of a jump back to the home screen.
 *
 * What it must never become is a loop
 * -----------------------------------
 * The first version of this guard did exactly that. It reopened the app after any
 * crash, up to three times in ten minutes — and the crash it was dealing with
 * happened a few seconds *into* every start, so reopening ran the same code to the
 * same failure. The person watching saw Undal appear, disappear, appear,
 * disappear, and then the home screen. "It reopens and reopens and then it
 * crashes" was the report.
 *
 * The fix is not a smaller number. It is that a crash is two different events and
 * the guard now treats them differently:
 *
 *  · **a crash during startup** means the opening code is broken, and reopening
 *    runs the opening code. One retry is enough to prove that; the second failure
 *    goes to [SafeModeActivity], which cannot fail because it depends on nothing.
 *  · **a crash later** is a screen, a payload or a listener, and reopening really
 *    does help, so it gets the ordinary budget of three in ten minutes — and when
 *    that runs out the answer is still safe mode, still not the home screen.
 *
 * The counting is in [CrashRecoveryPolicy], which is pure and therefore tested.
 *
 * Three rules the guard keeps for itself:
 *
 *  1. **It cannot break a release that never crashes.** It is installed in one
 *     line, replaces exactly one handler, and every call inside it is wrapped: if
 *     anything here failed, the platform's own handler still runs.
 *  2. **It only ever starts Undal's own activities**, with the flags the launcher
 *     uses, so the system never ends up holding two windows.
 *  3. **The crash is never thrown away.** The exception, the version, the device
 *     and the restart counts go to Crashlytics *and* into a report the person on
 *     the phone can copy and send, because the fastest crash to fix is the one the
 *     owner already has in their hand.
 */
object UndalCrashGuard {

    private const val TAG = "UndalCrashGuard"
    private const val PREFS = "undal_crash_guard"
    private const val KEY_WINDOW_START = "window_start"
    private const val KEY_RESTARTS = "restarts"
    private const val KEY_STARTUP_RESTARTS = "startup_restarts"
    private const val KEY_SAFE_MODES = "safe_modes"
    private const val KEY_LAST_REPORT = "last_report"

    /** How many stack frames the copyable report carries. Enough to find the file. */
    private const val REPORT_FRAMES = 14

    private const val RESTART_EXIT_CODE = 10
    private const val FATAL_EXIT_CODE = 1

    /** The clock the report's timestamp is written in, because that is the owner's. */
    private const val TIME_ZONE_DHAKA = "Asia/Dhaka"

    @Volatile
    private var installed = false

    /** When this process began, on the clock that survives a wall-clock change. */
    @Volatile
    private var processStartElapsed = 0L

    /**
     * Installs the guard. Called once, as early as possible, from
     * `UndalApp.onCreate` — before anything that could be slow, so that a crash
     * during the rest of the startup is still caught, counted and explained.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        processStartElapsed = SystemClock.elapsedRealtime()
        val app = context.applicationContext
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val outcome = runCatching { handle(app, thread, error, platformHandler) }
            if (outcome.isFailure) {
                // The guard itself failed. The crash is the platform's business
                // again, exactly as if this file did not exist.
                Log.e(TAG, "the crash guard failed while handling a crash", outcome.exceptionOrNull())
                handOver(platformHandler, thread, error)
            }
        }
    }

    private fun handle(
        app: Context,
        thread: Thread,
        error: Throwable,
        platformHandler: Thread.UncaughtExceptionHandler?
    ) {
        val ledger = readLedger(app)
        val age = SystemClock.elapsedRealtime() - processStartElapsed
        val outcome = CrashRecoveryPolicy.decide(System.currentTimeMillis(), age, ledger)
        val report = buildReport(error, thread, age, outcome.ledger)

        // Written before anything is dispatched: `commit` rather than `apply`,
        // because this process is about to die and a queued write would die with
        // it — which would make the loop protection useless exactly when it is
        // needed, and lose the report the person is about to be shown.
        writeLedger(app, outcome.ledger)
        prefs(app).edit().putString(KEY_LAST_REPORT, report).commit()

        reportToCrashlytics(error, thread, age, outcome)

        when (outcome.decision) {
            CrashRecoveryPolicy.Decision.Reopen -> {
                dispatch(app, recoveryIntent(app))
                sleepForHandover()
                die(RESTART_EXIT_CODE)
            }

            CrashRecoveryPolicy.Decision.SafeMode -> {
                Log.e(TAG, "Undal cannot start cleanly; opening the honest screen instead", error)
                dispatch(app, safeModeIntent(app))
                sleepForHandover()
                die(RESTART_EXIT_CODE)
            }

            CrashRecoveryPolicy.Decision.GiveUp -> {
                Log.e(TAG, "the crash budget is spent; handing the crash to Android", error)
                handOver(platformHandler, thread, error)
            }
        }
    }

    /**
     * The app got through its opening and stayed up, so the startup counters stop
     * applying. Called from the root composition once
     * [CrashRecoveryPolicy.SUCCESSFUL_START_MILLIS] has passed.
     */
    fun markSuccessfulStart(context: Context) {
        runCatching {
            val app = context.applicationContext
            writeLedger(app, CrashRecoveryPolicy.afterSuccessfulStart(readLedger(app)))
        }
    }

    /**
     * True when this launch is the app coming back from a guarded crash. The
     * activity reads it once and clears it, so a rotation does not repeat the
     * sentence.
     */
    fun isRecoveryLaunch(intent: Intent?): Boolean =
        intent?.getBooleanExtra(MainActivity.EXTRA_RECOVERED, false) == true

    /** The sentence the root shows after a recovered crash. */
    const val RECOVERY_SENTENCE: String =
        "Undal hit a problem and reopened itself. Nothing in your basket or your orders was lost."

    /** What the last crash was, in words, for [SafeModeActivity] to show. */
    fun lastReport(context: Context): String? =
        runCatching { prefs(context).getString(KEY_LAST_REPORT, null) }.getOrNull()

    fun clearLastReport(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_LAST_REPORT).apply() }
    }

    // ------------------------------------------------------------------ ledger

    private fun readLedger(app: Context): CrashRecoveryPolicy.Ledger {
        val prefs = runCatching { prefs(app) }.getOrNull() ?: return CrashRecoveryPolicy.Ledger.EMPTY
        return CrashRecoveryPolicy.Ledger(
            windowStart = prefs.getLong(KEY_WINDOW_START, 0L),
            restarts = prefs.getInt(KEY_RESTARTS, 0),
            startupRestarts = prefs.getInt(KEY_STARTUP_RESTARTS, 0),
            safeModes = prefs.getInt(KEY_SAFE_MODES, 0)
        )
    }

    private fun writeLedger(app: Context, ledger: CrashRecoveryPolicy.Ledger) {
        prefs(app).edit()
            .putLong(KEY_WINDOW_START, ledger.windowStart)
            .putInt(KEY_RESTARTS, ledger.restarts)
            .putInt(KEY_STARTUP_RESTARTS, ledger.startupRestarts)
            .putInt(KEY_SAFE_MODES, ledger.safeModes)
            .commit()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ report

    /**
     * The report the person is shown and can copy.
     *
     * Deliberately plain text and deliberately specific: version, Android, device,
     * the time in Dhaka, how long the process lived, what threw, and the frames —
     * Undal's own frames first, because those are the ones that name a file in
     * this repository.
     */
    private fun buildReport(
        error: Throwable,
        thread: Thread,
        processAgeMillis: Long,
        ledger: CrashRecoveryPolicy.Ledger
    ): String = buildString {
        appendLine("Undal ${safeVersionName()}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${safeDevice()}")
        appendLine("At ${dhakaTimestamp()} · the app had been open ${(processAgeMillis / 1000L)} s")
        appendLine("Thread: ${thread.name}")
        appendLine()
        appendLine("${error.javaClass.name}: ${error.message ?: "(no message)"}")
        framesOf(error).forEach { frame -> appendLine("    at $frame") }
        error.cause?.let { cause ->
            appendLine()
            appendLine("Caused by ${cause.javaClass.name}: ${cause.message ?: "(no message)"}")
            framesOf(cause).take(REPORT_FRAMES / 2).forEach { frame -> appendLine("    at $frame") }
        }
        appendLine()
        appendLine(
            "Restarts in the last ten minutes: ${ledger.restarts} · " +
                "startup retries: ${ledger.startupRestarts} · safe-mode screens: ${ledger.safeModes}"
        )
    }.trimEnd()

    /** Undal's own frames first: they are the ones that name a file in this app. */
    private fun framesOf(error: Throwable): List<String> {
        val all = error.stackTrace.orEmpty().map { it.toString() }
        val ours = all.filter { it.contains("com.undal") }
        val rest = all.filterNot { it.contains("com.undal") }
        return (ours + rest).take(REPORT_FRAMES)
    }

    private fun safeVersionName(): String =
        runCatching { UndalBuildInfo.versionName }.getOrDefault("unknown version")

    private fun safeDevice(): String = runCatching {
        listOfNotNull(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
    }.getOrDefault("unknown device").ifBlank { "unknown device" }

    private fun dhakaTimestamp(): String = runCatching {
        val format = SimpleDateFormat("d MMM yyyy, h:mm:ss a", Locale.ENGLISH)
        format.timeZone = TimeZone.getTimeZone(TIME_ZONE_DHAKA)
        format.format(Date())
    }.getOrDefault("an unknown time")

    // -------------------------------------------------------------- crashlytics

    private fun reportToCrashlytics(
        error: Throwable,
        thread: Thread,
        processAgeMillis: Long,
        outcome: CrashRecoveryPolicy.Outcome
    ) {
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("undal.fatal", error.javaClass.name)
            crashlytics.setCustomKey("undal.fatal.thread", thread.name)
            crashlytics.setCustomKey("undal.fatal.processAgeSeconds", processAgeMillis / 1000L)
            crashlytics.setCustomKey("undal.fatal.decision", outcome.decision.javaClass.simpleName)
            crashlytics.setCustomKey("undal.fatal.restarts", outcome.ledger.restarts)
            crashlytics.setCustomKey("undal.fatal.startupRestarts", outcome.ledger.startupRestarts)
            crashlytics.setCustomKey("undal.fatal.safeModes", outcome.ledger.safeModes)
            crashlytics.log("undal: ${error.javaClass.simpleName} on ${thread.name}")
            crashlytics.recordException(error)
        }
        runCatching { Log.e(TAG, "uncaught exception on ${thread.name}; ${outcome.decision}", error) }
    }

    // ----------------------------------------------------------------- intents

    private fun recoveryIntent(app: Context): Intent =
        Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(MainActivity.EXTRA_RECOVERED, true)
        }

    private fun safeModeIntent(app: Context): Intent =
        Intent(app, SafeModeActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

    private fun dispatch(app: Context, intent: Intent) {
        runCatching { app.startActivity(intent) }
            .onFailure { Log.e(TAG, "the recovery screen could not be started", it) }
    }

    private fun handOver(
        platformHandler: Thread.UncaughtExceptionHandler?,
        thread: Thread,
        error: Throwable
    ) {
        if (platformHandler != null) {
            runCatching { platformHandler.uncaughtException(thread, error) }
        }
        die(FATAL_EXIT_CODE)
    }

    /**
     * The pause that lets the system read a dispatched intent before the process
     * goes. Short enough not to be felt, long enough to matter on a busy phone.
     */
    private fun sleepForHandover() {
        runCatching { Thread.sleep(CrashRecoveryPolicy.HANDOVER_MILLIS) }
    }

    private fun die(code: Int) {
        Process.killProcess(Process.myPid())
        exitProcess(code)
    }

}
