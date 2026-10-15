package com.undal.syl.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash guard's arithmetic, pinned.
 *
 * This is the decision that produced the two complaints this app kept getting:
 * "it crashes to the home screen" when the budget was too small to matter, and
 * "it reopens and reopens and then it crashes" when it was too generous to stop.
 * A phone cannot be asked to demonstrate either one on demand, so the policy is
 * pure and it is tested instead.
 */
class CrashRecoveryPolicyTest {

    /** A wall clock and a process that began this long before it. */
    private val now = 1_800_000_000_000L

    private fun decide(
        ageMillis: Long,
        ledger: CrashRecoveryPolicy.Ledger = CrashRecoveryPolicy.Ledger.EMPTY,
        at: Long = now
    ) = CrashRecoveryPolicy.decide(at, ageMillis, ledger)

    @Test
    fun aFirstCrashReopensTheApp() {
        val outcome = decide(ageMillis = 60_000L)
        assertEquals(CrashRecoveryPolicy.Decision.Reopen, outcome.decision)
        assertEquals(1, outcome.ledger.restarts)
        assertEquals(0, outcome.ledger.startupRestarts)
        assertEquals(now, outcome.ledger.windowStart)
    }

    @Test
    fun aCrashDuringStartupReopensOnceAndThenStops() {
        // The exact shape of the reported bug: the app crashes a few seconds into
        // every start, so every reopen runs the same opening code to the same
        // failure. The second one is not a third attempt at the app — it is the
        // screen that says what happened.
        val first = decide(ageMillis = 3_000L)
        assertEquals(CrashRecoveryPolicy.Decision.Reopen, first.decision)
        assertEquals(1, first.ledger.startupRestarts)

        val second = decide(ageMillis = 2_500L, ledger = first.ledger, at = now + 5_000L)
        assertEquals(CrashRecoveryPolicy.Decision.SafeMode, second.decision)
        assertEquals(1, second.ledger.safeModes)
    }

    @Test
    fun aStartupCrashIsOneThatHappensInsideTheOpeningWindow() {
        val justInside = decide(ageMillis = CrashRecoveryPolicy.STARTUP_WINDOW_MILLIS)
        assertEquals(1, justInside.ledger.startupRestarts)

        val justOutside = decide(ageMillis = CrashRecoveryPolicy.STARTUP_WINDOW_MILLIS + 1L)
        assertEquals(0, justOutside.ledger.startupRestarts)
        assertEquals(CrashRecoveryPolicy.Decision.Reopen, justOutside.decision)
    }

    @Test
    fun anOrdinaryCrashGetsTheWholeBudgetAndThenSafeMode() {
        var ledger = CrashRecoveryPolicy.Ledger.EMPTY
        repeat(CrashRecoveryPolicy.MAX_RESTARTS) { attempt ->
            val outcome = decide(ageMillis = 120_000L, ledger = ledger, at = now + attempt * 30_000L)
            assertEquals(
                "reopen ${attempt + 1} of ${CrashRecoveryPolicy.MAX_RESTARTS}",
                CrashRecoveryPolicy.Decision.Reopen,
                outcome.decision
            )
            ledger = outcome.ledger
        }
        val exhausted = decide(ageMillis = 120_000L, ledger = ledger, at = now + 120_000L)
        assertEquals(CrashRecoveryPolicy.Decision.SafeMode, exhausted.decision)
    }

    @Test
    fun theBudgetComesBackAfterTheWindowWithoutAnyoneAsking() {
        var ledger = CrashRecoveryPolicy.Ledger.EMPTY
        repeat(CrashRecoveryPolicy.MAX_RESTARTS) {
            ledger = decide(ageMillis = 120_000L, ledger = ledger).ledger
        }
        assertEquals(
            CrashRecoveryPolicy.Decision.SafeMode,
            decide(ageMillis = 120_000L, ledger = ledger).decision
        )

        val later = now + CrashRecoveryPolicy.WINDOW_MILLIS + 1L
        val fresh = decide(ageMillis = 120_000L, ledger = ledger, at = later)
        assertEquals(CrashRecoveryPolicy.Decision.Reopen, fresh.decision)
        assertEquals(1, fresh.ledger.restarts)
        assertEquals(later, fresh.ledger.windowStart)
    }

    @Test
    fun aClockThatWentBackwardsStartsANewWindowInsteadOfAnEnormousOne() {
        // A timezone change or a network time sync on first boot can move the wall
        // clock backwards. Read as "a very old window" that would look like an
        // expired budget forever; read honestly it is a window that has not begun.
        val ledger = CrashRecoveryPolicy.Ledger(windowStart = now, restarts = 3, startupRestarts = 1)
        val outcome = decide(ageMillis = 120_000L, ledger = ledger, at = now - 60_000L)
        assertEquals(CrashRecoveryPolicy.Decision.Reopen, outcome.decision)
        assertEquals(now - 60_000L, outcome.ledger.windowStart)
        assertEquals(1, outcome.ledger.restarts)
    }

    @Test
    fun safeModeIsOfferedTwiceAndThenTheGuardGivesUp() {
        // Safe mode is an activity with nothing in it, so it should never crash.
        // If it somehow does, the guard must still end rather than cycle.
        val spent = CrashRecoveryPolicy.Ledger(
            windowStart = now,
            restarts = 1,
            startupRestarts = 1,
            safeModes = CrashRecoveryPolicy.MAX_SAFE_MODES
        )
        assertEquals(CrashRecoveryPolicy.Decision.GiveUp, decide(ageMillis = 1_000L, ledger = spent).decision)
    }

    @Test
    fun aRetryFromSafeModeIsStillCountedSoItCannotLoop() {
        // The person taps "try again", the app fails the same way, and the guard
        // has to notice. Resetting the counters on a retry is what would turn
        // safe mode into the loop it was written to end.
        val afterSafeMode = CrashRecoveryPolicy.Ledger(
            windowStart = now,
            restarts = 1,
            startupRestarts = 1,
            safeModes = 1
        )
        val retry = decide(ageMillis = 2_000L, ledger = afterSafeMode, at = now + 60_000L)
        assertEquals(CrashRecoveryPolicy.Decision.SafeMode, retry.decision)
        assertEquals(2, retry.ledger.safeModes)
    }

    @Test
    fun stayingUpClearsTheStartupCountersButNotTheWindow() {
        val ledger = CrashRecoveryPolicy.Ledger(
            windowStart = now,
            restarts = 2,
            startupRestarts = 1,
            safeModes = 1
        )
        val settled = CrashRecoveryPolicy.afterSuccessfulStart(ledger)
        assertEquals(0, settled.startupRestarts)
        assertEquals(0, settled.safeModes)
        // Two crashes inside ten minutes is still two crashes inside ten minutes:
        // a phone that fails every forty seconds must not reset itself forever.
        assertEquals(2, settled.restarts)
        assertEquals(now, settled.windowStart)
    }

    @Test
    fun theSuccessfulStartWindowCannotOverlapTheStartupWindow() {
        // If it could, a crash would be counted as a startup crash *after* the app
        // had already been declared to have started, and the two counters would
        // argue about the same event.
        assertTrue(
            CrashRecoveryPolicy.SUCCESSFUL_START_MILLIS > CrashRecoveryPolicy.STARTUP_WINDOW_MILLIS
        )
    }

    @Test
    fun aStartupCrashStillCountsAgainstTheOrdinaryBudget() {
        // Both counters move for one event, so a phone that fails on the way in
        // every ten seconds cannot keep reopening for ten whole minutes.
        val outcome = decide(ageMillis = 1_000L)
        assertEquals(1, outcome.ledger.restarts)
        assertEquals(1, outcome.ledger.startupRestarts)
    }

    @Test
    fun theHandoverPauseIsShortEnoughToBeUnfeltAndLongEnoughToMatter() {
        assertTrue(CrashRecoveryPolicy.HANDOVER_MILLIS in 50L..500L)
    }
}
