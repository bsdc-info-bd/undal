package com.undal.syl.app.core.util

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException

/**
 * A tiny result type so repositories never throw at the UI.
 *
 * Errors that reach a repository are already translated into a sentence the
 * owner or the customer can act on — never "ERROR_INTERNAL" on screen.
 */
sealed interface UndalResult<out T> {
    data class Success<T>(val value: T) : UndalResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : UndalResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
    fun messageOrNull(): String? = (this as? Failure)?.message
}

inline fun <T, R> UndalResult<T>.map(transform: (T) -> R): UndalResult<R> = when (this) {
    is UndalResult.Success -> UndalResult.Success(transform(value))
    is UndalResult.Failure -> this
}

inline fun <T> UndalResult<T>.onSuccess(action: (T) -> Unit): UndalResult<T> {
    if (this is UndalResult.Success) action(value)
    return this
}

inline fun <T> UndalResult<T>.onFailure(action: (String) -> Unit): UndalResult<T> {
    if (this is UndalResult.Failure) action(message)
    return this
}

/** Translates the Firebase exception zoo into one clear instruction. */
object UndalErrors {

    fun auth(error: Throwable): String = when (error) {
        is FirebaseAuthWeakPasswordException -> "Please choose a longer password (6+ characters)."
        is FirebaseAuthInvalidCredentialsException -> "That email or password is not correct."
        is FirebaseAuthInvalidUserException -> "We could not find an account with that email."
        is FirebaseAuthUserCollisionException -> "An account already exists with that email. Try logging in instead."
        is FirebaseNetworkException -> "No internet connection. Check your data or Wi-Fi and try again."
        is IOException -> "No internet connection. Check your data or Wi-Fi and try again."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
    }

    /**
     * Translates a Firestore failure into a sentence.
     *
     * [operation] is the plain-language thing that was being attempted — "reading
     * your profile", "saving your address". When the failure is one a person
     * cannot act on by themselves (a permission refusal, a signed-out request,
     * anything unrecognised) the sentence is followed by one line the kitchen can
     * quote back: the Firebase project this build is configured with, what it was
     * doing, and the database's own error code. Every other failure — offline,
     * not found — is self-explanatory and gets no reference line, because nobody
     * should have to read our diagnostics to understand a sentence.
     */
    fun firestore(error: Throwable, operation: String = ""): String {
        val sentence = when (error) {
            is FirebaseFirestoreException -> when (error.code) {
                // A rule refusal arrives with no explanation at all — Firestore
                // answers "Missing or insufficient permissions" and nothing else —
                // so this sentence has to carry the two things that actually cause
                // one: a field the rules require and the app did not satisfy, or an
                // account that is not the kitchen's. Naming both turns a dead end
                // into something the owner can check in a minute.
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "The database refused that. On a kitchen edit it means one field did not match the " +
                        "rules it is checked against — a name shorter than three characters, a price above " +
                        "৳ 10,000, a combo with fewer than two dishes, a campaign that ends before it " +
                        "starts — or that this account is not carrying the kitchen's vendor sign-in. " +
                        "On a read it means this account cannot see that collection."
                FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    "Your sign-in has expired. Please sign in again."
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    "You appear to be offline. This will finish as soon as you reconnect."
                FirebaseFirestoreException.Code.NOT_FOUND -> "That item is no longer available."
                FirebaseFirestoreException.Code.ALREADY_EXISTS -> "That already exists."
                FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
                    "This action is not allowed right now."
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                    "The connection is very slow. Please try again."
                else -> error.message?.takeIf { it.isNotBlank() }
                    ?: "The kitchen database rejected that request."
            }
            is FirebaseNetworkException -> "No internet connection. Check your data or Wi-Fi and try again."
            is IOException -> "You appear to be offline. Your changes are safe and will sync when you reconnect."
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
        }
        val code = (error as? FirebaseFirestoreException)?.code
        if (code == null) {
            // Not a Firestore failure at all — still worth the reference, because
            // this is the path an unexpected error takes.
            return sentence + "\n\nKitchen reference: " + reference("UNKNOWN", operation)
        }
        if (code != FirebaseFirestoreException.Code.PERMISSION_DENIED &&
            code != FirebaseFirestoreException.Code.UNAUTHENTICATED
        ) {
            return sentence
        }
        return sentence + "\n\nKitchen reference: " + reference(code.name, operation)
    }

    /**
     * The three facts that turn "it does not work" into something fixable: the
     * Firebase project this build was compiled against, what the app was doing,
     * and the database's own error code. The project id is public — it names the
     * `.firebaseapp.com` sign-in page — and knowing it is the difference between
     * a one-line fix and a week of guessing.
     */
    private fun reference(code: String, operation: String): String {
        val project = runCatching { FirebaseApp.getInstance().options.projectId }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "project not in this build"
        val parts = mutableListOf(project)
        if (operation.isNotBlank()) parts.add(operation)
        parts.add(code)
        return parts.joinToString(" · ")
    }

    fun generic(error: Throwable, fallback: String = "Something went wrong. Please try again."): String =
        error.message?.takeIf { it.isNotBlank() } ?: fallback
}
