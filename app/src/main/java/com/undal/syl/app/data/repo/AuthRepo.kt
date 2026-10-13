package com.undal.syl.app.data.repo

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.maps.UndalLocation
import com.undal.syl.app.core.session.OnboardingProgress
import com.undal.syl.app.core.util.UndalErrors
import com.undal.syl.app.core.util.UndalResult
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

    private companion object {
        const val USERS = "users"
    }
}
