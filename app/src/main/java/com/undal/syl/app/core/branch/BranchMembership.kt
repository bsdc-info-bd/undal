package com.undal.syl.app.core.branch

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.model.StoreConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** One kitchen the signed-in vendor is allowed to operate. */
data class VendorBranch(
    val id: String,
    val name: String,
    val store: StoreConfig,
    val legacySingleBranch: Boolean = false,
) {
    fun asCustomerSelection(): BranchSelection = BranchSelection(
        branchId = id,
        branchName = name,
        store = store,
        status = "deliverable",
        distanceKm = null,
        deliveryPaisa = 0L,
        legacySingleBranch = legacySingleBranch,
    )
}

/**
 * Vendor-side branch membership. Firestore rules independently enforce these
 * claims; the local list is navigation, never an authorization boundary.
 */
@Singleton
class BranchMembership @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val dataStore: DataStore<Preferences>,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val selection: Flow<VendorBranch> = dataStore.data.map { it[SELECTED_BRANCH] }
        .flatMapLatest { id -> flow { emit(resolve(id)) } }

    suspend fun current(): VendorBranch = resolve(dataStore.data.first()[SELECTED_BRANCH])

    private suspend fun resolve(preferredId: String?): VendorBranch {
        val branches = availableBranches()
        return branches.firstOrNull { it.id == preferredId }
            ?: branches.firstOrNull()
            ?: VendorBranch("default", "Undal HomeKitchen", StoreConfig.unloaded(), legacySingleBranch = true)
    }

    suspend fun availableBranches(): List<VendorBranch> {
        val user = auth.currentUser ?: return emptyList()
        val claims = runCatching { user.getIdToken(false).await().claims }.getOrDefault(emptyMap())
        val assignments = (claims["branchIds"] as? List<*>).orEmpty().mapNotNull { it as? String }
        val superAdmin = claims["superAdmin"] == true
        val snapshot = runCatching { firestore.collection("branches").get().await() }.getOrNull()
        val documents = snapshot?.documents.orEmpty().filter { it.getBoolean("active") != false }
        if (documents.isEmpty()) {
            // Before migration, an empty branchIds claim means the original store.
            return if (superAdmin || assignments.isEmpty() || assignments.any { it == "*" || it == "default" }) {
                val store = runCatching {
                    firestore.collection("config").document("store").get().await()
                }.getOrNull()?.data?.let { StoreConfig.fromMap(it) } ?: StoreConfig.unloaded()
                listOf(VendorBranch("default", store.storeName, store, legacySingleBranch = true))
            } else emptyList()
        }
        return documents.mapNotNull { document ->
            val id = document.id
            val allowed = superAdmin || assignments.contains("*") || assignments.contains(id) ||
                (assignments.isEmpty() && id == "default")
            if (!allowed) return@mapNotNull null
            val store = StoreConfig.fromMap(document.data.orEmpty())
            val name = document.getString("name").orEmpty().ifBlank { store.storeName }
            VendorBranch(id, name, store.copy(storeName = name))
        }.sortedWith(compareBy<VendorBranch> { it.id != "default" }.thenBy { it.name })
    }

    suspend fun select(branchId: String): VendorBranch {
        val branch = availableBranches().firstOrNull { it.id == branchId }
            ?: error("Your account is not assigned to that branch.")
        dataStore.edit { it[SELECTED_BRANCH] = branch.id }
        return branch
    }

    private companion object {
        val SELECTED_BRANCH = stringPreferencesKey("vendor_selected_branch")
    }
}
