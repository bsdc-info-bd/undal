package com.undal.syl.app.data.repo

import java.util.Locale
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.model.Category
import com.undal.syl.app.core.model.Combo
import com.undal.syl.app.core.model.MenuCard
import com.undal.syl.app.core.model.MenuItem
import com.undal.syl.app.core.model.MenuSnapshot
import com.undal.syl.app.core.model.MenuSort
import com.undal.syl.app.core.model.Subcategory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The menu, kept live (C-024 … C-037).
 *
 * Five small public collections stream in parallel and are combined into one
 * snapshot. Everything the customer does next — searching, sorting, filtering,
 * the sold-out badge, the popular chip — happens in memory on that snapshot,
 * which is why the store screen feels instant on a 3G connection.
 *
 * The vendor's own ordering is respected: `order` ascending, never alphabetical,
 * because the cook arranged the menu the way people actually order.
 */
@Singleton
class MenuRepo @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    fun observeMenu(): Flow<MenuSnapshot> = combine(
        observeCategories(),
        observeSubcategories(),
        observeItems(),
        observeCombos(),
        observeCampaigns()
    ) { categories, subcategories, items, combos, campaigns ->
        MenuSnapshot(
            categories = categories,
            subcategories = subcategories,
            items = items,
            combos = combos,
            campaigns = campaigns,
            loaded = true
        )
    }

    private fun observeCategories(): Flow<List<Category>> = callbackFlow {
        val registration = firestore.collection(CATEGORIES).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(
                snapshot?.documents.orEmpty()
                    .mapNotNull { document ->
                        val map = document.data ?: return@mapNotNull null
                        Category.fromDocument(document.id, map).takeIf { it.name.isNotBlank() }
                    }
                    .sortedBy { it.order }
            )
        }
        awaitClose { registration.remove() }
    }.catch { emit(emptyList()) }

    private fun observeSubcategories(): Flow<List<Subcategory>> = callbackFlow {
        val registration = firestore.collection(SUBCATEGORIES).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(
                snapshot?.documents.orEmpty()
                    .mapNotNull { document ->
                        val map = document.data ?: return@mapNotNull null
                        Subcategory.fromDocument(document.id, map).takeIf { it.name.isNotBlank() }
                    }
                    .sortedBy { it.order }
            )
        }
        awaitClose { registration.remove() }
    }.catch { emit(emptyList()) }

    private fun observeItems(): Flow<List<MenuItem>> = callbackFlow {
        val registration = firestore.collection(MENU_ITEMS).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(
                snapshot?.documents.orEmpty()
                    .mapNotNull { document ->
                        val map = document.data ?: return@mapNotNull null
                        MenuItem.fromDocument(document.id, map).takeIf { it.name.isNotBlank() }
                    }
                    .sortedBy { it.name }
            )
        }
        awaitClose { registration.remove() }
    }.catch { emit(emptyList()) }

    private fun observeCombos(): Flow<List<Combo>> = callbackFlow {
        val registration = firestore.collection(COMBOS).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(
                snapshot?.documents.orEmpty().mapNotNull { document ->
                    val map = document.data ?: return@mapNotNull null
                    Combo.fromDocument(document.id, map).takeIf { it.name.isNotBlank() }
                }
            )
        }
        awaitClose { registration.remove() }
    }.catch { emit(emptyList()) }

    private fun observeCampaigns(): Flow<List<com.undal.syl.app.core.model.Campaign>> = callbackFlow {
        val registration = firestore.collection(CAMPAIGNS).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val now = System.currentTimeMillis()
            trySend(
                snapshot?.documents.orEmpty().mapNotNull { document ->
                    com.undal.syl.app.core.model.Campaign
                        .fromDocument(document.id, document.data.orEmpty())
                        ?.takeIf { it.isLive(now) }
                }
            )
        }
        awaitClose { registration.remove() }
    }.catch { emit(emptyList()) }

    /**
     * The filter pipeline used by the store screen: category → subcategory →
     * search → sort. Kept as a pure function so the same rules can be unit-tested
     * and reused by the vendor's preview in Response 4.
     */
    fun filter(
        snapshot: MenuSnapshot,
        categoryId: String?,
        subcategoryId: String?,
        query: String,
        sort: MenuSort
    ): List<MenuCard> {
        val cards = snapshot.cards.filter { card ->
            when {
                categoryId == null -> true
                categoryId == MenuCard.COMBO_CATEGORY -> card is MenuCard.Bundle
                else -> card.categoryId == categoryId
            }
        }.filter { card ->
            subcategoryId == null || (card as? MenuCard.Dish)?.item?.subcategoryId == subcategoryId
        }.filter { card ->
            card.searchText.contains(query.trim().lowercase(Locale.ENGLISH))
        }

        val popularity = snapshot.items.associate { it.id to it.orderCount }
        return sort.apply(cards, popularity)
    }

    private companion object {
        const val CATEGORIES = "categories"
        const val SUBCATEGORIES = "subcategories"
        const val MENU_ITEMS = "menuItems"
        const val COMBOS = "combos"
        const val CAMPAIGNS = "campaigns"
    }
}
