package com.undal.syl.app.core.session

import com.google.firebase.firestore.FirebaseFirestore
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

    suspend fun resolve(): RoleResolution {
        val user = session.currentUser ?: return RoleResolution.NotSignedIn
        var isVendor = claimIsVendor(user.getIdToken(false).await().claims)

        if (!isVendor && userRegisteredAsVendor(user.uid)) {
            // The account is the kitchen but the cached token does not say so:
            // the claim was granted after this phone signed in. One forced refresh
            // settles it, and only the kitchen ever pays for it — a customer's
            // `vendor/{uid}` does not exist, so their cold start is unchanged.
            isVendor = claimIsVendor(user.getIdToken(true).await().claims)
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
                com.undal.syl.app.core.util.UndalErrors.firestore(error, operation = "reading your profile")
            )
        }
    }

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
