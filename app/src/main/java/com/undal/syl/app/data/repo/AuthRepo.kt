package com.undal.syl.app.data.repo

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.maps.UndalLocation
import com.undal.syl.app.core.session.OnboardingProgress
import com.undal.syl.app.core.util.UndalErrors
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.core.util.Validators
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Customer accounts and their profile document (C-003 … C-020).
 *
 * The two steps are deliberately separate:
 *   1. [register] creates the Firebase Auth account and remembers the typed name
 *      and phone on the device,
 *   2. [saveCustomerProfile] writes `users/{uid}` — with the mandatory address —
 *      once the customer has pinned their delivery point.
 *
 * That order matters: the security rules reject a profile without a complete
 * address, so a half-finished registration can never enter the database. If the
 * customer is interrupted (a phone call, a flat battery), the draft on the
 * device brings them straight back to the location step.
 */
@Singleton
class AuthRepo @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val progress: OnboardingProgress
) {

    /** Creates the account and stores the draft profile. Sends the verify email. */
    suspend fun register(name: String, email: String, phone: String, password: String): UndalResult<Unit> {
        return runCatching {
            val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: error("Firebase returned no user for the new account")

            runCatching {
                user.updateProfile(
                    UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()
                ).await()
            }
            runCatching { user.sendEmailVerification().await() }

            progress.saveDraftProfile(
                OnboardingProgress.DraftProfile(name = name.trim(), email = email.trim(), phone = phone.filter { it.isDigit() })
            )
            Unit
        }.fold(
            onSuccess = { UndalResult.Success(Unit) },
            onFailure = { UndalResult.Failure(UndalErrors.auth(it), it) }
        )
    }

    suspend fun login(email: String, password: String): UndalResult<Unit> = runCatching {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        Unit
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.auth(it), it) }
    )

    suspend fun sendPasswordReset(email: String): UndalResult<Unit> = runCatching {
        auth.sendPasswordResetEmail(email.trim()).await()
        Unit
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.auth(it), it) }
    )

    suspend fun resendVerificationEmail(): UndalResult<Unit> = runCatching {
        val user = auth.currentUser ?: error("Not signed in")
        user.sendEmailVerification().await()
        Unit
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.auth(it), it) }
    )

    /**
     * Writes `users/{uid}` in a transaction once the address is complete
     * (C-008 · C-019). The transaction both creates a brand-new profile and
     * updates an existing one, so re-pinning an address works through the same
     * path.
     */
    suspend fun saveCustomerProfile(
        name: String,
        phone: String,
        location: UndalLocation
    ): UndalResult<Unit> {
        val user = auth.currentUser
            ?: return UndalResult.Failure("Please sign in again — your session expired.")
        if (!location.isValid) {
            return UndalResult.Failure("The address still needs a title, a description and a delivery note.")
        }

        return runCatching {
            val document = firestore.collection(USERS).document(user.uid)
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(document)
                val address = location.toFirestoreMap()
                if (snapshot.exists()) {
                    transaction.update(
                        document,
                        mapOf(
                            "name" to name.trim(),
                            "phone" to phone.filter { it.isDigit() },
                            "address" to address
                        )
                    )
                } else {
                    transaction.set(
                        document,
                        mapOf(
                            "name" to name.trim(),
                            "email" to (user.email ?: ""),
                            "phone" to phone.filter { it.isDigit() },
                            "photoUrl" to "",
                            "role" to "customer",
                            "fcmSub" to mapOf("promos" to true),
                            "address" to address,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                null
            }.await()
            progress.clearDraftAddress()
            Unit
        }.fold(
            onSuccess = { UndalResult.Success(Unit) },
            onFailure = { UndalResult.Failure(UndalErrors.firestore(it), it) }
        )
    }

    /** The address this customer already saved, if any (used to pre-fill the picker). */
    suspend fun loadProfileLocation(): UndalLocation? {
        val uid = auth.currentUser?.uid ?: return null
        return runCatching {
            val snapshot = firestore.collection(USERS).document(uid).get().await()
            @Suppress("UNCHECKED_CAST")
            UndalLocation.fromFirestoreMap(snapshot.get("address") as? Map<String, Any>)
        }.getOrNull()
    }

    suspend fun loadProfileName(): String? {
        val uid = auth.currentUser?.uid ?: return null
        return runCatching {
            firestore.collection(USERS).document(uid).get().await().getString("name")
        }.getOrNull()
    }

    /** The phone number the rider will call, as saved on the profile. */
    suspend fun loadProfilePhone(): String? {
        val uid = auth.currentUser?.uid ?: return null
        return runCatching {
            firestore.collection(USERS).document(uid).get().await().getString("phone")
        }.getOrNull()
    }

    /** True when a profile document exists with a complete address. */
    suspend fun hasCompleteProfile(): Boolean = loadProfileLocation()?.isValid == true

    /**
     * Full account deletion for the customer (C-080): the profile document, the
     * FCM token documents and the auth account itself, in that order, so no
     * orphaned data can keep a token alive.
     */
    suspend fun deleteAccount(): UndalResult<Unit> {
        val user = auth.currentUser
            ?: return UndalResult.Failure("Please sign in again — your session expired.")
        return runCatching {
            val tokens = firestore.collection("fcmTokens").document(user.uid).collection("tokens")
                .get().await()
            val batch = firestore.batch()
            batch.delete(firestore.collection(USERS).document(user.uid))
            tokens.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
            user.delete().await()
            Unit
        }.fold(
            onSuccess = { UndalResult.Success(Unit) },
            onFailure = {
                UndalResult.Failure(
                    "Could not delete the account: ${UndalErrors.auth(it)} " +
                        "If this keeps happening, sign out and sign in again, then retry.",
                    it
                )
            }
        )
    }

    /** Whether the verification link sent at registration has been confirmed. */
    fun isEmailVerified(): Boolean = auth.currentUser?.isEmailVerified ?: false

    /** The signed-in email; shown read-only on the profile screen (C-079). */
    fun currentEmail(): String? = auth.currentUser?.email

    /** The Cloudinary photo link saved on the profile, if there is one. */
    suspend fun loadProfilePhoto(): String? {
        val uid = auth.currentUser?.uid ?: return null
        return runCatching {
            firestore.collection(USERS).document(uid).get().await().getString("photoUrl")
        }.getOrNull()
    }

    /**
     * Saves a new profile photo URL (C-079).
     *
     * The document update keeps `role` and the address untouched, which is what
     * the security rules insist on: a profile write may never lose the pinned
     * address the kitchen delivers to.
     */
    suspend fun saveProfilePhoto(url: String): UndalResult<Unit> {
        val uid = auth.currentUser?.uid
            ?: return UndalResult.Failure("Please sign in again — your session expired.")
        return runCatching {
            firestore.collection(USERS).document(uid).update("photoUrl", url.trim()).await()
            Unit
        }.fold(
            onSuccess = { UndalResult.Success(Unit) },
            onFailure = { UndalResult.Failure(UndalErrors.firestore(it), it) }
        )
    }

    /**
     * Edits the name and the phone number (C-079).
     *
     * Both are written together because both are what the rider works from, and
     * a half-saved pair ("new name, old number") is worse than no save at all.
     */
    suspend fun saveProfileBasics(name: String, phone: String): UndalResult<Unit> {
        val uid = auth.currentUser?.uid
            ?: return UndalResult.Failure("Please sign in again — your session expired.")

        val cleanName = name.trim()
        if (cleanName.length < 2 || cleanName.length > 80) {
            return UndalResult.Failure("Please enter the name the rider should ask for (2–80 characters).")
        }
        val cleanPhone = Validators.normalizeBdMobile(phone)
            ?: return UndalResult.Failure("Please enter a Bangladeshi mobile number like 01712345678.")

        return runCatching {
            firestore.collection(USERS).document(uid)
                .update(mapOf("name" to cleanName, "phone" to cleanPhone))
                .await()
            Unit
        }.fold(
            onSuccess = { UndalResult.Success(Unit) },
            onFailure = { UndalResult.Failure(UndalErrors.firestore(it), it) }
        )
    }

    /**
     * Changes the password after re-authenticating with the current one (C-079).
     *
     * Firebase requires a recent sign-in for a password change, so the current
     * password is not decoration: it is the proof that the person holding the
     * unlocked phone is the account holder.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): UndalResult<Unit> {
        val user = auth.currentUser
            ?: return UndalResult.Failure("Please sign in again — your session expired.")
        val email = user.email
            ?: return UndalResult.Failure("This account has no email address, so the password cannot be changed here.")
        if (currentPassword.isBlank()) return UndalResult.Failure("Enter your current password.")
        if (newPassword.length < 6) return UndalResult.Failure("Choose a new password with at least 6 characters.")
        if (newPassword == currentPassword) {
            return UndalResult.Failure("The new password is the same as the old one.")
        }
        return runCatching {
            user.reauthenticate(EmailAuthProvider.getCredential(email, currentPassword)).await()
            user.updatePassword(newPassword).await()
            Unit
        }.fold(
            onSuccess = { UndalResult.Success(Unit) },
            onFailure = { error ->
                val message = if (error is FirebaseAuthInvalidCredentialsException) {
                    "Your current password is not correct."
                } else {
                    UndalErrors.auth(error)
                }
                UndalResult.Failure(message, error)
            }
        )
    }

    private companion object {
        const val USERS = "users"
    }
}
