package com.undal.syl.app.data.repo

import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.config.UndalBuildInfo
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
            // The link the app sends is the one the customer has to be able to
            // come back from. Firebase's default handler confirms the address in
            // the browser and then shows the continue URL, so the customer lands
            // on the storefront rather than on a blank page — and our Worker's
            // `/app` route is what turns that landing into "open Undal".
            runCatching {
                user.sendEmailVerification(verificationSettings()).await()
                lastVerificationSentAt = System.currentTimeMillis()
            }

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

    /**
     * Sends the confirmation link again (C-009).
     *
     * The same three-step guard every "resend" needs: signed in, not already
     * verified, and not asking Firebase twice inside a minute — a customer who
     * taps Resend three times because the first mail was slow gets one link, not
     * three, and never the `TOO_MANY_REQUESTS` that a fourth would produce.
     */
    suspend fun resendVerificationEmail(): UndalResult<Unit> {
        val user = auth.currentUser
            ?: return UndalResult.Failure("Please sign in again — your session expired.")
        if (user.isEmailVerified) {
            return UndalResult.Success(Unit)
        }
        val now = System.currentTimeMillis()
        if (now - lastVerificationSentAt < RESEND_COOLDOWN_MILLIS) {
            val seconds = ((RESEND_COOLDOWN_MILLIS - (now - lastVerificationSentAt)) / 1000).toInt() + 1
            return UndalResult.Failure("A link was just sent. Give it $seconds seconds before asking again.")
        }
        return runCatching {
            user.sendEmailVerification(verificationSettings()).await()
            lastVerificationSentAt = now
            Unit
        }.fold(
            onSuccess = { UndalResult.Success(Unit) },
            onFailure = { UndalResult.Failure(UndalErrors.auth(it), it) }
        )
    }

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

        // Everything below is checked here because the security rules check it
        // too, and a rule that refuses a write answers with nothing but "Missing
        // or insufficient permissions" — which reads to a customer as a broken
        // kitchen. One concrete sentence at a time is the difference between a
        // fixable field and a dead end.
        val cleanName = name.trim().take(MAX_NAME_CHARS)
        if (Validators.name(cleanName) != null) {
            return UndalResult.Failure(
                "Please add your full name before saving the address — the kitchen calls it out on the bag."
            )
        }
        // The rules store the number as 01XXXXXXXXX, so the same normaliser the
        // registration form uses is applied here: a number typed as +880 1712
        // 345678 would otherwise be refused by the database.
        val cleanPhone = Validators.normalizeBdMobile(phone)
            ?: return UndalResult.Failure(
                "We do not have a usable mobile number for this account, and the rider has to call you. " +
                    "Add it as 01XXXXXXXXX and save again."
            )
        val cleanEmail = user.email?.takeIf { Validators.email(it) == null }
            ?: return UndalResult.Failure(
                "This account has no email address on it, so the profile cannot be saved. " +
                    "Please sign out and create the account again with your email."
            )
        val descriptionProblem = Validators.addressDescription(location.desc)
        val noteProblem = Validators.deliveryNote(location.note)
        if (descriptionProblem != null || noteProblem != null) {
            return UndalResult.Failure(descriptionProblem ?: noteProblem.orEmpty())
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
                            "name" to cleanName,
                            "phone" to cleanPhone,
                            "address" to address
                        )
                    )
                } else {
                    transaction.set(
                        document,
                        mapOf(
                            "name" to cleanName,
                            "email" to cleanEmail,
                            "phone" to cleanPhone,
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
            onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "saving your address"), it) }
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

    /**
     * Whether the confirmation link sent at registration has been confirmed.
     *
     * **This is a server question, not a local one.** `FirebaseUser` caches the
     * account record it last downloaded, and tapping the link in a mail app
     * changes that record on Google's side while no listener on this device fires
     * — the SDK has no idea anything happened. Reading the cached flag alone is
     * why a customer who had just verified their address still saw "your email is
     * not verified yet".
     *
     * So: a cached `true` is trusted (it can only be replaced by another reload),
     * and a cached `false` triggers [`FirebaseUser.reload`] — one small call that
     * fetches the real record — before the answer is given. A reload that cannot
     * reach the network leaves the previous answer in place rather than inventing
     * a new one, and [verified] returning false is what the caller turns into
     * "we could not check just now".
     */
    suspend fun isEmailVerified(): Boolean {
        val user = auth.currentUser ?: return false
        if (user.isEmailVerified) return true
        runCatching { user.reload().await() }
        return user.isEmailVerified
    }

    /** True when the last check actually reached Firebase. */
    suspend fun checkEmailVerifiedWithNetwork(): Pair<Boolean, Boolean> {
        val user = auth.currentUser ?: return false to false
        if (user.isEmailVerified) return true to true
        val reached = runCatching { user.reload().await() }.isSuccess
        return user.isEmailVerified to reached
    }

    /** The address the confirmation link was sent to, for the sentence on screen. */
    fun currentEmailAddress(): String? = auth.currentUser?.email

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

        /** The security rules cap the stored name at 80 characters. */
        const val MAX_NAME_CHARS = 80

        /** Firebase itself refuses a burst; one a minute is the polite version. */
        const val RESEND_COOLDOWN_MILLIS = 60_000L
    }

    /**
     * Where the confirmation mail sends the customer when the link is opened.
     *
     * The Worker's `/app` route is the app's own deep-link landing page: it
     * answers on a phone with the intent that opens Undal, and on a desktop with
     * the storefront. Without a continue URL Firebase shows its own generic page,
     * which is where a customer confirms their address and then has nowhere to go.
     */
    private fun verificationSettings(): ActionCodeSettings {
        val continueUrl = UndalBuildInfo.webStoreBaseUrl?.let { it.trimEnd('/') + "/app" }
        return ActionCodeSettings.newBuilder()
            // A build without the owner's host has no page to land on: Firebase's
            // own confirmation page is a better destination than a sub-domain that
            // does not resolve. The address is still verified either way.
            .apply { if (continueUrl != null) setUrl(continueUrl) }
            .setHandleCodeInApp(false)
            .build()
    }

    /**
     * When a confirmation link was last sent, so the button cannot be used as a
     * mail bomb against the account holder's own inbox.
     */
    @Volatile
    private var lastVerificationSentAt: Long = 0L
}
