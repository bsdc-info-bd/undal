package com.undal.syl.app.core.util

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

    fun firestore(error: Throwable): String = when (error) {
        is FirebaseFirestoreException -> when (error.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "The kitchen has not published this yet, or your account cannot read it."
            FirebaseFirestoreException.Code.UNAVAILABLE ->
                "You appear to be offline. This will finish as soon as you reconnect."
            FirebaseFirestoreException.Code.NOT_FOUND -> "That item is no longer available."
            FirebaseFirestoreException.Code.ALREADY_EXISTS -> "That already exists."
            FirebaseFirestoreException.Code.FAILED_PRECONDITION -> "This action is not allowed right now."
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> "The connection is very slow. Please try again."
            else -> error.message?.takeIf { it.isNotBlank() } ?: "The kitchen database rejected that request."
        }
        is FirebaseNetworkException -> "No internet connection. Check your data or Wi-Fi and try again."
        is IOException -> "You appear to be offline. Your changes are safe and will sync when you reconnect."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
    }

    fun generic(error: Throwable, fallback: String = "Something went wrong. Please try again."): String =
        error.message?.takeIf { it.isNotBlank() } ?: fallback
}
