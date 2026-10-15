package com.undal.syl.app.core.session

import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.util.UndalErrors
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Which portal this device should show. */
enum class UndalRole { CUSTOMER, VENDOR, UNKNOWN }

/**
 * Decides the role from the custom claim first (authoritative, cannot be forged)
 * and the profile documents second (a useful consistency check that also tells
 * us whether the customer still needs to finish the location step).
 *
 * A signed-in customer with no `users/{uid}` document has not completed
 * registration on this device yet → [RoleResolution.NeedsProfile].
 *
 * A cached token can be up to an hour old, and a claim added in that window —
 * the kitchen account being provisioned while the phone was already signed in —
 * is invisible until it refreshes. That is not cosmetic: `isVendor()` is what
 * every order read depends on, so a kitchen device with a stale token cannot
 * see the orders it is meant to ring for. Rather than force a refresh on every
 * cold start, the claim is re-read once when the cached one says "not the
 * kitchen" and the profile check that follows cannot decide either — the two
 * cases where being wrong costs the most.
 */
@Singleton
class RoleGate @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val session: AuthSession
) {

    sealed interface RoleResolution {
        data class Routed(val role: UndalRole, val hasLocation: Boolean) : RoleResolution
        data object NeedsProfile : RoleResolution
        data object NotSignedIn : RoleResolution
        data class Failed(val message: String) : RoleResolution
    }

    /**
     * Decides where this phone belongs. It answers with a sentence rather than an
     * exception, always — see [Routed], [NeedsProfile], [NotSignedIn], [Failed].
     *
     * That contract is not decoration. This runs inside the root router's
     * coroutine, and an exception that escapes a `viewModelScope` job is an
     * uncaught exception: the process is killed and the person is looking at
     * their home screen. The ordinary case that produced it was plain and cruel —
     * signing in on a *new* phone, where the token and the `vendor/{uid}` check
     * are the first reads the device has ever made and the connection is often
     * the weakest it will ever be.
     */
    suspend fun resolve(): RoleResolution = runCatching { decide() }.getOrElse { error ->
        RoleResolution.Failed(
            UndalErrors.firestore(error, operation = "opening your account")
        )
    }

    private suspend fun decide(): RoleResolution {
        val user = session.currentUser ?: return RoleResolution.NotSignedIn

        // A cached read: no network, no wait, and it is what the installed app
        // already has in hand. If even this fails the phone simply has no claim
        // to read yet, which the document check below can still settle.
        var isVendor = readClaim(user, forceRefresh = false)

        if (!isVendor && userRegisteredAsVendor(user.uid)) {
            // The account is the kitchen but the cached token does not say so:
            // the claim was granted after this phone signed in. One forced refresh
            // settles it, and only the kitchen ever pays for it — a customer's
            // `vendor/{uid}` does not exist, so their cold start is unchanged.
            val refreshed = runCatching { user.getIdToken(true).await() }
            when {
                refreshed.isSuccess -> isVendor = claimIsVendor(refreshed.getOrNull()?.claims)

                // No connection is not a refusal, and the two need opposite
                // answers. `vendor/{uid}` was written by the provisioning tool and
                // cannot be forged by a client, this phone has it in its cache
                // because it has opened the console before, and Firestore will read
                // every order the kitchen already has from that same cache. So an
                // offline kitchen gets its console — with the rules, not this
                // router, deciding what it may actually read — instead of a red
                // screen telling it to check a connection it cannot fix from here.
                UndalErrors.isOffline(refreshed.exceptionOrNull()) -> isVendor = true

                else ->
                    // The document says kitchen, the server was reached and would
                    // not confirm it. Rather than showing a console whose every
                    // read is refused, say what happened and offer another try.
                    return RoleResolution.Failed(
                        "This phone holds the kitchen's account, but Undal could not confirm it with the server just now. " +
                            "Check the connection and try again — the kitchen's orders are safe while you do."
                    )
            }
        }

        if (isVendor) {
            return RoleResolution.Routed(role = UndalRole.VENDOR, hasLocation = true)
        }

        return runCatching {
            val snapshot = firestore.collection(USERS).document(user.uid).get().await()
            if (!snapshot.exists()) {
                return@runCatching RoleResolution.NeedsProfile
            }
            val address = snapshot.get("address") as? Map<*, *>
            val hasCoordinates = (address?.get("lat") as? Number) != null && (address?.get("lng") as? Number) != null
            val hasText = !(address?.get("title") as? String).isNullOrBlank() &&
                !(address?.get("desc") as? String).isNullOrBlank() &&
                !(address?.get("note") as? String).isNullOrBlank()
            RoleResolution.Routed(role = UndalRole.CUSTOMER, hasLocation = hasCoordinates && hasText)
        }.getOrElse { error ->
            RoleResolution.Failed(
                UndalErrors.firestore(error, operation = "reading your profile")
            )
        }
    }

    /** The cached claim, or false when it cannot be read at all. Never throws. */
    private suspend fun readClaim(user: com.google.firebase.auth.FirebaseUser, forceRefresh: Boolean): Boolean =
        runCatching { claimIsVendor(user.getIdToken(forceRefresh).await().claims) }.getOrDefault(false)

    private fun claimIsVendor(claims: Map<String, Any>?): Boolean =
        claims?.get(AuthSession.VENDOR_CLAIM_KEY) == true

    /**
     * `vendor/{uid}` is written by the provisioning tool. Its presence means this
     * account is the kitchen even if the cached token has not caught up — and it
     * is what says whether the forced refresh above is worth its round trip.
     */
    private suspend fun userRegisteredAsVendor(uid: String): Boolean = runCatching {
        firestore.collection(VENDOR).document(uid).get().await().exists()
    }.getOrDefault(false)

    /** True when this device must not show the vendor portal (V-008 wrong-role guard). */
    fun isWrongRole(isVendorClaim: Boolean, expectedVendor: Boolean): Boolean = isVendorClaim != expectedVendor

    private companion object {
        const val USERS = "users"
        const val VENDOR = "vendor"
    }
}
