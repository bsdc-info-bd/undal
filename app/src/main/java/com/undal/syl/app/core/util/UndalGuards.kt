package com.undal.syl.app.core.util

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * The rule about coroutines in this app, written once.
 *
 * An exception that escapes a `viewModelScope.launch` is an *uncaught* exception:
 * `viewModelScope` is a `SupervisorJob`, so nothing above it catches anything,
 * and Android's answer to an uncaught exception is to kill the process. To the
 * person holding the phone that is not an error message — the app vanishes and
 * the launcher is on screen. `UndalCrashGuard` then reopens it, up to three
 * times in ten minutes, and when that budget is gone the app simply closes. That
 * is the whole of the reported symptom "it reopens and then goes back to the home
 * screen", and the guard is the last line of defence, not the fix.
 *
 * The fix is that no ViewModel coroutine is allowed to end the process. Each one
 * carries a handler built here, which does three things and nothing else:
 *
 *   1. logs the throwable, because a failure nobody recorded is a failure that
 *      happens again;
 *   2. turns it into one sentence through [UndalErrors], the same translator the
 *      repositories use, so a customer reads "you appear to be offline" rather
 *      than a stack trace;
 *   3. hands that sentence to the ViewModel, which puts it where its own screen
 *      already shows messages.
 *
 * Every step is wrapped. A handler that can itself throw would be worse than no
 * handler at all, because it runs on the way to the process's death.
 */
object UndalGuards {

    private const val TAG = "UndalGuard"

    /**
     * A handler for a ViewModel's coroutines.
     *
     * @param operation what was being attempted, in the words the person would
     *   use — "reading the menu", "saving your address". It ends up in the
     *   sentence and in the kitchen reference line.
     * @param onError where the sentence goes: the ViewModel's own state field.
     */
    fun viewModel(operation: String, onError: (String) -> Unit): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, error ->
            runCatching { Log.w(TAG, "$operation failed", error) }
            val message = runCatching { UndalErrors.firestore(error, operation = operation) }
                .getOrDefault("Something went wrong. Please try again.")
            runCatching { onError(message) }
        }

    /**
     * A handler for work with no screen to speak on — a push service, a receiver,
     * a watchdog. The rule is the same and the reason is stronger: these run in a
     * process the person is not looking at, so a crash here closes an app that was
     * only doing its job in the background.
     *
     * There is nowhere to put a sentence, so the throwable is logged and, where
     * Crashlytics is available, recorded — and then swallowed deliberately.
     *
     * Named `service` rather than `background`: `tools/check_import_contracts.py`
     * polices every bare `background(` call in the app as Compose's
     * `Modifier.background()`, which is an extension that has to be imported, and
     * a function of our own with that name trips a gate that is right about
     * everything else it checks.
     */
    fun service(operation: String): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, error ->
            runCatching { Log.w(TAG, "$operation failed in the background", error) }
            runCatching {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                    .log("undal.background.$operation: ${error.javaClass.simpleName}")
            }
        }
}
