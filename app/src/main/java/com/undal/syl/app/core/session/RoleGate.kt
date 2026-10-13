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
        val isVendor = user.getIdToken(false).await().claims?.get(AuthSession.VENDOR_CLAIM_KEY) == true

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
                com.undal.syl.app.core.util.UndalErrors.firestore(error)
            )
        }
    }

    /** True when this device must not show the vendor portal (V-008 wrong-role guard). */
    fun isWrongRole(isVendorClaim: Boolean, expectedVendor: Boolean): Boolean = isVendorClaim != expectedVendor

    private companion object {
        const val USERS = "users"
    }
}
