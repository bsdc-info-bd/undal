package com.undal.syl.app.core.util

/**
 * What the crash guard does next, decided without touching Android.
 *
 * The guard has one job and two ways to fail it. Reopen too little and a person
 * who tapped an icon is standing on their home screen wondering what happened —
 * which is what Undal did, and the complaint was "it crashes to the home screen".
 * Reopen too much and the app becomes a loop that puts itself back on screen
 * faster than it can be dismissed — which is what Undal did next, and the
 * complaint was "it reopens and reopens and then it crashes".
 *
 * Both failures come from asking one number ("how many restarts are left?") to
 * answer two different questions. A crash eight seconds after the process began
 * is a *startup* crash: reopening runs the same opening code, so it will fail the
 * same way, and a second attempt is the most anyone should be asked to watch. A
 * crash two hours in is something else — a screen, a payload, a listener — and
 * there reopening genuinely helps, so it gets the full budget.
 *
 * And when a budget runs out the answer is not the home screen. It is
 * [Decision.SafeMode]: an activity with no Compose, no Hilt and no Firebase in
 * it, which says what happened and offers a way back. The person keeps their
 * place in the conversation instead of losing the app, and the owner gets a
 * report they can copy and send.
 *
 * This file is pure on purpose. The decision is the part that has to be right,
 * it is the part a phone cannot be trusted to demonstrate, and it is the part
 * `CrashRecoveryPolicyTest` can therefore prove on every build.
 */
object CrashRecoveryPolicy {

    /**
     * How long after the process began a crash still counts as a startup crash.
     * Eight seconds covers a cold start on the slowest phone this app supports
     * without swallowing a genuine failure on a screen the person reached.
     */
    const val STARTUP_WINDOW_MILLIS = 8_000L

    /** One reopen for a startup crash. A second identical failure is a loop. */
    const val MAX_STARTUP_RESTARTS = 1

    /** The budget for everything else, inside [WINDOW_MILLIS]. */
    const val MAX_RESTARTS = 3

    /** The sliding window the ordinary budget is counted inside. */
    const val WINDOW_MILLIS = 10L * 60L * 1000L

    /**
     * How many times safe mode may be offered before the guard gives up. Two, so
     * a crash *inside* safe mode — which should be impossible, since that activity
     * depends on nothing — still ends instead of cycling.
     */
    const val MAX_SAFE_MODES = 2

    /** The pause that lets the system read a restart intent before the process dies. */
    const val HANDOVER_MILLIS = 180L

    sealed interface Decision {
        /** Open the app again where it was. */
        data object Reopen : Decision

        /** The app cannot start on its own. Open the honest screen instead. */
        data object SafeMode : Decision

        /** Nothing left to try: hand the crash to the platform's own handler. */
        data object GiveUp : Decision
    }

    /**
     * What has been spent inside the current window.
     *
     * Every field is a plain number so the whole ledger can live in one
     * `SharedPreferences` file and survive the process dying — which it is about
     * to do, and which is why the guard writes it with `commit`.
     */
    data class Ledger(
        val windowStart: Long = 0L,
        val restarts: Int = 0,
        val startupRestarts: Int = 0,
        val safeModes: Int = 0
    ) {
        companion object {
            val EMPTY = Ledger()
        }
    }

    /** A decision and the ledger to write down next to it. */
    data class Outcome(val decision: Decision, val ledger: Ledger)

    /**
     * @param nowMillis the wall clock, which is what the window is measured in
     *   because it has to survive the process dying and being replaced
     * @param processAgeMillis how long this process has been alive; a crash inside
     *   [STARTUP_WINDOW_MILLIS] of that is a startup crash
     * @param ledger what has already been spent
     */
    fun decide(nowMillis: Long, processAgeMillis: Long, ledger: Ledger): Outcome {
        // A clock that went backwards (a timezone change, a network time sync on
        // first boot) must not be read as an enormous window. Start a new one.
        val windowExpired = ledger.windowStart <= 0L ||
            ledger.windowStart > nowMillis ||
            nowMillis - ledger.windowStart > WINDOW_MILLIS
        val windowStart = if (windowExpired) nowMillis else ledger.windowStart
        val spent = if (windowExpired) Ledger.EMPTY else ledger

        val startupCrash = processAgeMillis in 0..STARTUP_WINDOW_MILLIS

        return when {
            spent.safeModes >= MAX_SAFE_MODES ->
                Outcome(Decision.GiveUp, spent)

            startupCrash && spent.startupRestarts >= MAX_STARTUP_RESTARTS ->
                Outcome(Decision.SafeMode, spent.copy(safeModes = spent.safeModes + 1))

            spent.restarts >= MAX_RESTARTS ->
                Outcome(Decision.SafeMode, Ledger(windowStart, 0, 0, spent.safeModes + 1))

            else -> Outcome(
                Decision.Reopen,
                Ledger(
                    windowStart = windowStart,
                    restarts = spent.restarts + 1,
                    startupRestarts = spent.startupRestarts + if (startupCrash) 1 else 0,
                    safeModes = spent.safeModes
                )
            )
        }
    }

    /**
     * The app opened and stayed open, so the startup counters stop applying.
     *
     * The ordinary budget and its window are deliberately kept: a phone that
     * crashes every forty seconds would otherwise reset itself forever, and ten
     * minutes of that is exactly what [MAX_RESTARTS] exists to notice.
     */
    fun afterSuccessfulStart(ledger: Ledger): Ledger =
        ledger.copy(startupRestarts = 0, safeModes = 0)

    /**
     * How long the app has to stay up before a start counts as successful.
     * Longer than [STARTUP_WINDOW_MILLIS] so the two windows cannot overlap, and
     * short enough that a person who reached a screen and used it for half a
     * minute is not still paying for the crash that opened the app.
     */
    const val SUCCESSFUL_START_MILLIS = 20_000L
}
