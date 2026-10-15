package com.undal.syl.app.core.util

import com.google.firebase.FirebaseNetworkException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * "Is this phone offline, or did the server refuse it?" pinned.
 *
 * The question looks trivial and its answer is not, because Firebase wraps what it
 * throws: an auth call that could not reach the network arrives as a
 * `FirebaseException` around a `SocketTimeoutException`, and a Firestore read
 * arrives as `UNAVAILABLE`. Reading only the outer type answers "something went
 * wrong" — which is how an offline kitchen came to be shown a red error screen on
 * every cold start instead of its own console and the orders already in its cache.
 *
 * These are JDK and `firebase-common` types only, both of which a plain JVM unit
 * test can construct. Nothing here needs a phone, which is the point: the phone is
 * the one place this distinction cannot be demonstrated on demand.
 */
class UndalErrorsTest {

    @Test
    fun noConnectionIsRecognisedWhateverShapeItArrivesIn() {
        assertTrue(UndalErrors.isOffline(FirebaseNetworkException("A network error occurred")))
        assertTrue(UndalErrors.isOffline(IOException("boom")))
        assertTrue(UndalErrors.isOffline(SocketTimeoutException("timeout")))
    }

    @Test
    fun noConnectionIsRecognisedFromTheWordsTheSdkUses() {
        assertTrue(UndalErrors.isOffline(RuntimeException("NETWORK_ERROR")))
        assertTrue(UndalErrors.isOffline(RuntimeException("UNAVAILABLE: backend unreachable")))
        assertTrue(UndalErrors.isOffline(RuntimeException("Unable to resolve host \"firestore.googleapis.com\"")))
        assertTrue(UndalErrors.isOffline(RuntimeException("No address associated with hostname")))
        assertTrue(UndalErrors.isOffline(RuntimeException("failed to connect to /10.0.2.2:443")))
        assertTrue(UndalErrors.isOffline(RuntimeException("Connection timed out")))
        assertTrue(UndalErrors.isOffline(RuntimeException("No internet connection")))
    }

    @Test
    fun aRefusalIsNotReadAsNoConnection() {
        assertFalse(UndalErrors.isOffline(RuntimeException("PERMISSION_DENIED: missing or insufficient permissions")))
        assertFalse(UndalErrors.isOffline(IllegalStateException("The claim could not be read")))
        assertFalse(UndalErrors.isOffline(RuntimeException("NOT_FOUND")))
        assertFalse(UndalErrors.isOffline(null))
    }

    @Test
    fun theCauseChainIsWalkedBecauseFirebaseWrapsWhatItThrows() {
        assertTrue(
            UndalErrors.isOffline(
                RuntimeException("The token could not be refreshed", SocketTimeoutException("timed out"))
            )
        )
        assertTrue(
            UndalErrors.isOffline(
                RuntimeException(
                    "outer",
                    RuntimeException("middle", FirebaseNetworkException("A network error occurred"))
                )
            )
        )
    }

    @Test
    fun aCauseChainLongerThanTheLimitCannotSpin() {
        // A chain with no answer at any depth has to terminate rather than recurse:
        // this runs while the app is trying to open, and a stack overflow there is
        // the crash the guard exists to explain.
        var error: Throwable = RuntimeException("the bottom")
        repeat(12) { error = RuntimeException("level $it", error) }
        assertFalse(UndalErrors.isOffline(error))
    }

    @Test
    fun theSameDistinctionDrivesWhatThePersonIsTold() {
        // `isOffline` is not a curiosity: it is what decides between "use the
        // cache and open the console" and "say the server could not be reached".
        // The sentence has to match the answer, or the two halves of the app
        // disagree in front of the person using it.
        val offline = UndalErrors.firestore(FirebaseNetworkException("A network error occurred"))
        assertTrue(UndalErrors.isOffline(FirebaseNetworkException("A network error occurred")))
        assertTrue(offline.startsWith("No internet connection."))
    }

    @Test
    fun anUnexpectedFailureStillGetsASentenceAndSomethingQuotable() {
        val unexpected = UndalErrors.firestore(RuntimeException(""), operation = "saving the menu")
        assertTrue(unexpected.isNotBlank())
        // The reference line is what turns "it does not work" into something the
        // kitchen can read back over the phone, so it is never dropped.
        assertTrue(unexpected.contains("Kitchen reference:"))
    }
}
