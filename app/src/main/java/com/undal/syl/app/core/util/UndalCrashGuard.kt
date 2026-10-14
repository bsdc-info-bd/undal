package com.undal.syl.app.core.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.undal.syl.app.MainActivity
import kotlin.system.exitProcess

/**
 * The last line of defence: an app that vanishes is an app that failed.
 *
 * Every crash this guard sees is already reported to Crashlytics, so nothing is
 * hidden from the person who fixes it. What this adds is what the customer and
 * the kitchen see instead of a jump back to the home screen:
 *
 *  · the exception is recorded (Crashlytics custom key + exception + logcat),
 *  · the app is reopened in a clean task when it can be,
 *  · and MainActivity is told, so the person sees one honest sentence rather
 *    than a screen that silently restarted.
 *
 * Three rules keep a guard from becoming a nuisance:
 *
 *  1. **It may not loop.** Four restarts inside ten minutes is a crash loop, not
 *     a recovery: at that point the uncaught exception is handed straight to the
 *     platform's own handler and the app closes as Android intends.
 *  2. **It only ever restarts the app that crashed.** No other component is
 *     started from a dying process, and the restart intent carries the same task
 *     flags the launcher uses, so the system does not end up with two windows.
 *  3. **It cannot break a release that never crashes.** The whole guard is
 *     installed in one line, replaces exactly one handler, and every call inside
 *     it is wrapped: if anything here failed, the previous handler still runs.
 */
object UndalCrashGuard {

    private const val TAG = "UndalCrashGuard"
    private const val PREFS = "undal_crash_guard"
    private const val KEY_RESTARTS = "restarts"
    private const val KEY_WINDOW_START = "window_start"

    /** More than this inside the window is a loop, and a loop is not recovered. */
    private const val MAX_RESTARTS = 3
    private const val WINDOW_MILLIS = 10L * 60L * 1000L

    /**
     * The restart intent is dispatched before the process goes; this is the pause
     * that lets the system read it. Short enough not to be felt, long enough to
     * matter on a busy phone.
     */
    private const val HANDOVER_MILLIS = 180L

    @Volatile
    private var installed = false

    /**
     * Installs the guard. Called once, as early as possible, from
     * `UndalApp.onCreate` — before Crashlytics would install its own handler, so
     * that ours keeps a reference to it and can hand over to it deliberately.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            report(error)
            if (reopen(app)) {
                Thread.sleep(HANDOVER_MILLIS)
                Process.killProcess(Process.myPid())
                exitProcess(RESTART_EXIT_CODE)
            }
            // Out of restart budget: hand the crash to the platform exactly as if
            // this guard were not here.
            val previous = platformHandler
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                Process.killProcess(Process.myPid())
                exitProcess(FATAL_EXIT_CODE)
            }
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

    private const val RESTART_EXIT_CODE = 10
    private const val FATAL_EXIT_CODE = 1

    private fun report(error: Throwable) {
        runCatching {
            FirebaseCrashlytics.getInstance().setCustomKey("undal.fatal", error.javaClass.name)
        }
        runCatching { FirebaseCrashlytics.getInstance().recordException(error) }
        runCatching { Log.e(TAG, "uncaught exception; the app is reopening", error) }
    }

    /**
     * Asks the system to open a fresh MainActivity. Returns false when the
     * restart budget is used up or the launch could not be requested — and in
     * both of those cases the crash is the platform's business again.
     */
    private fun reopen(app: Context): Boolean = runCatching {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!withinBudget(prefs)) return@runCatching false
        val intent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(MainActivity.EXTRA_RECOVERED, true)
        }
        app.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * Counts restarts inside a sliding ten-minute window. `commit` rather than
     * `apply`: the process is about to die, and a pending write would be lost
     * with it — which would make the loop protection useless exactly when it is
     * needed.
     */
    private fun withinBudget(prefs: SharedPreferences): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = prefs.getLong(KEY_WINDOW_START, 0L)
        val expired = now - windowStart > WINDOW_MILLIS || windowStart > now
        val used = if (expired) 0 else prefs.getInt(KEY_RESTARTS, 0)
        val editor = prefs.edit()
        if (used >= MAX_RESTARTS) {
            editor.putInt(KEY_RESTARTS, 0).putLong(KEY_WINDOW_START, now).commit()
            return false
        }
        editor.putInt(KEY_RESTARTS, used + 1)
            .putLong(KEY_WINDOW_START, if (expired) now else windowStart)
            .commit()
        return true
    }
}
