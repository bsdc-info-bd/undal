package com.undal.syl.app.data.repo

import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.model.Campaign
import com.undal.syl.app.core.model.CampaignDraft
import com.undal.syl.app.core.model.Combo
import com.undal.syl.app.core.model.ComboDraft
import com.undal.syl.app.core.model.CouponDraft
import com.undal.syl.app.core.model.MenuDraft
import com.undal.syl.app.core.model.VendorCoupon
import com.undal.syl.app.core.net.CloudinaryUploader
import com.undal.syl.app.core.util.UndalErrors
import com.undal.syl.app.core.util.UndalResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every write the menu manager performs (V-049 … V-070).
 *
 * The shapes here are not free-form: `firestore.rules` validates exactly these
 * fields, and this class is written to satisfy it — positive prices inside the
 * cap, a real `createdAt` on the first write, an `updatedAt == request.time` on
 * every later one, combos with at least two dishes and a price no higher than
 * the sum of their parts.
 */
@Singleton
class VendorMenuRepo @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val uploader: CloudinaryUploader
) {

    // ------------------------------------------------------------------- reads

    /** Every campaign, live, scheduled or switched off (V-061, V-062). */
    fun observeCampaigns(): Flow<List<Campaign>> = callbackFlow {
        val registration = firestore.collection(CAMPAIGNS)
            .orderBy("startAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(60)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(
                    snapshot?.documents.orEmpty().mapNotNull { document ->
                        Campaign.fromDocument(document.id, document.data.orEmpty())
                    }
                )
            }
        awaitClose { registration.remove() }
    }.catch { emit(emptyList()) }

    /** Every coupon, newest first (V-063, V-064). */
    fun observeCoupons(): Flow<List<VendorCoupon>> = callbackFlow {
        val registration = firestore.collection(COUPONS)
            .limit(80)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(
                    snapshot?.documents.orEmpty()
                        .map { document -> VendorCoupon.fromDocument(document.id, document.data.orEmpty()) }
                        .sortedBy { it.code }
                )
            }
        awaitClose { registration.remove() }
    }.catch { emit(emptyList()) }

    /** How many of the last 200 orders contained a dish (V-055). */
    suspend fun recentOrdersUsing(itemName: String): Int = runCatching {
        firestore.collection(ORDERS)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(200)
            .get()
            .await()
            .documents
            .count { document ->
                val items = document.get("items") as? List<*>
                items.orEmpty().any { entry ->
                    val line = entry as? Map<*, *>
                    (line?.get("name") as? String)?.equals(itemName, ignoreCase = true) == true
                }
            }
    }.getOrDefault(0)

    // -------------------------------------------------------------- catalogue

    suspend fun saveCategory(
        id: String?,
        name: String,
        order: Int,
        active: Boolean,
        iconUrl: String
    ): UndalResult<String> = runCatching {
        val payload = mapOf(
            "name" to name.trim(),
            "order" to order,
            "active" to active,
            "iconUrl" to iconUrl
        )
        if (id == null) {
            val reference = firestore.collection(CATEGORIES).document()
            reference.set(payload).await()
            reference.id
        } else {
            firestore.collection(CATEGORIES).document(id).update(payload).await()
            id
        }
    }.fold(
        onSuccess = { UndalResult.Success(it) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "saving a category"), it) }
    )

    suspend fun deleteCategory(id: String): UndalResult<Unit> = runCatching {
        val usedByItems = firestore.collection(MENU_ITEMS)
            .whereEqualTo("categoryId", id)
            .limit(1)
            .get()
            .await()
            .isEmpty
            .not()
        if (usedByItems) {
            error("This category still holds dishes. Move them first.")
        }
        val usedBySubcategories = firestore.collection(SUBCATEGORIES)
            .whereEqualTo("categoryId", id)
            .limit(1)
            .get()
            .await()
            .isEmpty
            .not()
        if (usedBySubcategories) {
            error("This category still holds subcategories. Remove them first.")
        }
        firestore.collection(CATEGORIES).document(id).delete().await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(it.message ?: UndalErrors.firestore(it, operation = "deleting a category"), it) }
    )

    /** Persists a whole drag reorder in one batch (V-051). */
    suspend fun reorderCategories(orderedIds: List<String>): UndalResult<Unit> = runCatching {
        val batch = firestore.batch()
        orderedIds.forEachIndexed { index, id ->
            batch.update(firestore.collection(CATEGORIES).document(id), "order", index)
        }
        batch.commit().await()
        Unit
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "reordering categories"), it) }
    )

    suspend fun saveSubcategory(
        id: String?,
        categoryId: String,
        name: String,
        order: Int,
        active: Boolean
    ): UndalResult<String> = runCatching {
        val payload = mapOf(
            "categoryId" to categoryId,
            "name" to name.trim(),
            "order" to order,
            "active" to active
        )
        if (id == null) {
            val reference = firestore.collection(SUBCATEGORIES).document()
            reference.set(payload).await()
            reference.id
        } else {
            firestore.collection(SUBCATEGORIES).document(id).update(payload).await()
            id
        }
    }.fold(
        onSuccess = { UndalResult.Success(it) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "saving a subcategory"), it) }
    )

    suspend fun deleteSubcategory(id: String): UndalResult<Unit> = runCatching {
        val used = firestore.collection(MENU_ITEMS)
            .whereEqualTo("subcategoryId", id)
            .limit(1)
            .get()
            .await()
            .isEmpty
            .not()
        if (used) error("Dishes still use this subcategory. Move them first.")
        firestore.collection(SUBCATEGORIES).document(id).delete().await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(it.message ?: UndalErrors.firestore(it, operation = "deleting a subcategory"), it) }
    )

    suspend fun reorderSubcategories(orderedIds: List<String>): UndalResult<Unit> = runCatching {
        val batch = firestore.batch()
        orderedIds.forEachIndexed { index, id ->
            batch.update(firestore.collection(SUBCATEGORIES).document(id), "order", index)
        }
        batch.commit().await()
        Unit
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "reordering subcategories"), it) }
    )

    // ------------------------------------------------------------- menu items

    suspend fun saveItem(draft: MenuDraft): UndalResult<String> = runCatching {
        if (!draft.isValid) error(draft.nameError ?: draft.priceError ?: "Fill the required fields.")
        val fields = draft.writeFields().toMutableMap()
        fields["updatedAt"] = FieldValue.serverTimestamp()

        draft.id?.let { existingId ->
            firestore.collection(MENU_ITEMS).document(existingId).update(fields).await()
            return@runCatching existingId
        }

        // A first write must carry the fields the rules insist on, including a
        // trustworthy order count and a server timestamp.
        fields["orderCount"] = 0L
        fields["createdAt"] = FieldValue.serverTimestamp()
        val reference = firestore.collection(MENU_ITEMS).document()
        reference.set(fields).await()
        reference.id
    }.fold(
        onSuccess = { UndalResult.Success(it) },
        onFailure = { UndalResult.Failure(it.message ?: UndalErrors.firestore(it, operation = "saving a dish"), it) }
    )

    suspend fun deleteItem(id: String): UndalResult<Unit> = runCatching {
        firestore.collection(MENU_ITEMS).document(id).delete().await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "deleting a dish"), it) }
    )

    /** The sold-out switch — one tap, live on every customer screen (V-056). */
    suspend fun setItemAvailability(id: String, available: Boolean): UndalResult<Unit> =
        patchItem(id, mapOf("available" to available))

    suspend fun setItemFeatured(id: String, featured: Boolean): UndalResult<Unit> =
        patchItem(id, mapOf("featured" to featured))

    /** Bulk mode: one batch, one confirmation (V-057). */
    suspend fun setAvailabilityBulk(ids: List<String>, available: Boolean): UndalResult<Unit> = runCatching {
        val batch = firestore.batch()
        ids.forEach { id ->
            batch.update(
                firestore.collection(MENU_ITEMS).document(id),
                mapOf(
                    "available" to available,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.commit().await()
        Unit
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "changing availability for several dishes"), it) }
    )

    private suspend fun patchItem(id: String, extra: Map<String, Any?>): UndalResult<Unit> = runCatching {
        val payload = extra.toMutableMap()
        payload["updatedAt"] = FieldValue.serverTimestamp()
        firestore.collection(MENU_ITEMS).document(id).update(payload).await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "changing a dish"), it) }
    )

    // ----------------------------------------------------------------- combos

    suspend fun saveCombo(draft: ComboDraft): UndalResult<String> = runCatching {
        if (!draft.isValid) error(draft.error ?: "Fill the combo fields.")
        val fields = mapOf(
            "name" to draft.name.trim(),
            "description" to draft.description.trim().take(300),
            "amountPaisa" to (draft.pricePaisa ?: 0L),
            "regularAmountPaisa" to draft.regularPaisa,
            "itemRefs" to draft.refs.map { ref ->
                mapOf("menuItemId" to ref.menuItemId, "qty" to ref.qty)
            },
            "imageUrl" to draft.imageUrl,
            "available" to draft.available,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        draft.id?.let { existingId ->
            firestore.collection(COMBOS).document(existingId).update(fields).await()
            return@runCatching existingId
        }
        val reference = firestore.collection(COMBOS).document()
        reference.set(fields).await()
        reference.id
    }.fold(
        onSuccess = { UndalResult.Success(it) },
        onFailure = { UndalResult.Failure(it.message ?: UndalErrors.firestore(it, operation = "saving a combo"), it) }
    )

    suspend fun deleteCombo(id: String): UndalResult<Unit> = runCatching {
        firestore.collection(COMBOS).document(id).delete().await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "deleting a combo"), it) }
    )

    suspend fun setComboAvailability(id: String, available: Boolean): UndalResult<Unit> = runCatching {
        firestore.collection(COMBOS).document(id)
            .update(
                mapOf(
                    "available" to available,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "changing a combo's availability"), it) }
    )

    // -------------------------------------------------------------- campaigns

    suspend fun saveCampaign(draft: CampaignDraft): UndalResult<String> = runCatching {
        if (!draft.isValid) error(draft.error ?: "Fill the campaign fields.")
        val start = Timestamp(Date(draft.startAtMillis ?: 0L))
        val end = Timestamp(Date(draft.endAtMillis ?: 0L))
        val fields = mapOf(
            "title" to draft.title.trim(),
            "subtitle" to draft.subtitle.trim().take(120),
            "type" to draft.type,
            "value" to (draft.value ?: 0L),
            "startAt" to start,
            "endAt" to end,
            "bannerUrl" to draft.bannerUrl,
            "code" to draft.code.trim().uppercase(),
            "active" to draft.active,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        draft.id?.let { existingId ->
            firestore.collection(CAMPAIGNS).document(existingId).update(fields).await()
            return@runCatching existingId
        }
        val reference = firestore.collection(CAMPAIGNS).document()
        reference.set(fields).await()
        reference.id
    }.fold(
        onSuccess = { UndalResult.Success(it) },
        onFailure = { UndalResult.Failure(it.message ?: UndalErrors.firestore(it, operation = "saving a campaign"), it) }
    )

    suspend fun setCampaignActive(id: String, active: Boolean): UndalResult<Unit> = runCatching {
        firestore.collection(CAMPAIGNS).document(id).update("active", active).await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "switching a campaign"), it) }
    )

    suspend fun deleteCampaign(id: String): UndalResult<Unit> = runCatching {
        firestore.collection(CAMPAIGNS).document(id).delete().await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "deleting a campaign"), it) }
    )

    // ---------------------------------------------------------------- coupons

    suspend fun saveCoupon(draft: CouponDraft): UndalResult<String> = runCatching {
        if (!draft.isValid) error(draft.firstError ?: "Fill the coupon fields.")
        val code = draft.normalisedCode
        val fields = draft.writeFields().toMutableMap()
        if (draft.originalCode != null) {
            // Editing must never hand the coupon back its used-up allowance.
            fields.remove("usedCount")
        }
        if (draft.originalCode != null && draft.originalCode != code) {
            // Renaming a code creates the new one and retires the old one.
            firestore.collection(COUPONS).document(code).set(fields).await()
            firestore.collection(COUPONS).document(draft.originalCode).delete().await()
            return@runCatching code
        }
        firestore.collection(COUPONS).document(code)
            .set(fields, com.google.firebase.firestore.SetOptions.merge())
            .await()
        code
    }.fold(
        onSuccess = { UndalResult.Success(it) },
        onFailure = { UndalResult.Failure(it.message ?: UndalErrors.firestore(it, operation = "saving a coupon"), it) }
    )

    suspend fun setCouponActive(code: String, active: Boolean): UndalResult<Unit> = runCatching {
        firestore.collection(COUPONS).document(code).update("active", active).await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(UndalErrors.firestore(it, operation = "switching a coupon"), it) }
    )

    /** A coupon that has been used is retired, never deleted (V-065). */
    suspend fun deleteCoupon(code: String): UndalResult<Unit> = runCatching {
        val snapshot = firestore.collection(COUPONS).document(code).get().await()
        val used = snapshot.getLong("usedCount") ?: 0L
        if (used > 0L) {
            error("$code has already been used $used time(s). Switch it off instead — the orders still point at it.")
        }
        firestore.collection(COUPONS).document(code).delete().await()
    }.fold(
        onSuccess = { UndalResult.Success(Unit) },
        onFailure = { UndalResult.Failure(it.message ?: UndalErrors.firestore(it, operation = "deleting a coupon"), it) }
    )

    // ----------------------------------------------------------------- uploads

    /** Photo pick → Cloudinary, with a progress fraction for the button (V-053). */
    suspend fun uploadImage(uri: Uri, onProgress: (Float) -> Unit): UndalResult<String> =
        when (val result = uploader.uploadImage(uri, onProgress)) {
            is UndalResult.Success -> UndalResult.Success(result.value.secureUrl)
            is UndalResult.Failure -> result
        }

    private companion object {
        const val CATEGORIES = "categories"
        const val SUBCATEGORIES = "subcategories"
        const val MENU_ITEMS = "menuItems"
        const val COMBOS = "combos"
        const val CAMPAIGNS = "campaigns"
        const val COUPONS = "coupons"
        const val ORDERS = "orders"
    }
}
