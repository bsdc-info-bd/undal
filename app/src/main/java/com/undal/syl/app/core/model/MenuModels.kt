package com.undal.syl.app.core.model

import java.util.Locale
import com.undal.syl.app.core.util.Money

/**
 * The menu model (PART F · PART G).
 *
 * A single kitchen means a small, fast menu: the app loads the whole thing once
 * with live listeners and filters it on the phone. That is why search, sorting
 * and the sold-out badge all answer instantly, with no query round-trip and no
 * composite index.
 */
data class Category(
    val id: String,
    val name: String,
    val order: Int,
    val active: Boolean = true,
    val iconUrl: String = ""
) {
    companion object {
        fun fromDocument(id: String, map: Map<String, Any?>): Category = Category(
            id = id,
            name = (map["name"] as? String).orEmpty(),
            order = (map["order"] as? Number)?.toInt() ?: 0,
            active = map["active"] as? Boolean ?: true,
            iconUrl = (map["iconUrl"] as? String).orEmpty()
        )
    }
}

data class Subcategory(
    val id: String,
    val categoryId: String,
    val name: String,
    val order: Int,
    val active: Boolean = true
) {
    companion object {
        fun fromDocument(id: String, map: Map<String, Any?>): Subcategory = Subcategory(
            id = id,
            categoryId = (map["categoryId"] as? String).orEmpty(),
            name = (map["name"] as? String).orEmpty(),
            order = (map["order"] as? Number)?.toInt() ?: 0,
            active = map["active"] as? Boolean ?: true
        )
    }
}

/**
 * `menuItems/{itemId}`.
 *
 * `available` is the sold-out switch: the dish stays on the menu with a badge
 * instead of vanishing, because "finished for today" is information the customer
 * needs, not a hole in the page.
 */
data class MenuItem(
    val id: String,
    val name: String,
    val description: String,
    val amountPaisa: Long,
    val discountAmountPaisa: Long,
    val available: Boolean,
    val veg: Boolean,
    val spiceLevel: Int,
    val tags: List<String>,
    val orderCount: Long,
    val categoryId: String,
    val subcategoryId: String,
    val imageUrl: String,
    val prepMinutes: Int,
    val featured: Boolean = false
) {

    val hasDiscount: Boolean get() = discountAmountPaisa > 0 && discountAmountPaisa < amountPaisa

    /** What the customer actually pays for one. */
    val payablePaisa: Long get() = if (hasDiscount) amountPaisa - discountAmountPaisa else amountPaisa

    val isPopular: Boolean get() = orderCount >= POPULAR_THRESHOLD

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase(Locale.ENGLISH)
        return name.lowercase(Locale.ENGLISH).contains(needle) ||
            description.lowercase(Locale.ENGLISH).contains(needle) ||
            tags.any { it.lowercase(Locale.ENGLISH).contains(needle) }
    }

    companion object {
        /** Ten orders is the point where "popular" stops being a lie. */
        const val POPULAR_THRESHOLD = 10L

        fun fromDocument(id: String, map: Map<String, Any?>): MenuItem = MenuItem(
            id = id,
            name = (map["name"] as? String).orEmpty(),
            description = (map["description"] as? String).orEmpty(),
            amountPaisa = (map["amountPaisa"] as? Number)?.toLong() ?: 0L,
            discountAmountPaisa = (map["discountAmountPaisa"] as? Number)?.toLong() ?: 0L,
            available = map["available"] as? Boolean ?: false,
            veg = map["veg"] as? Boolean ?: false,
            spiceLevel = (map["spiceLevel"] as? Number)?.toInt() ?: 0,
            tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            orderCount = (map["orderCount"] as? Number)?.toLong() ?: 0L,
            categoryId = (map["categoryId"] as? String).orEmpty(),
            subcategoryId = (map["subcategoryId"] as? String).orEmpty(),
            imageUrl = (map["imageUrl"] as? String).orEmpty(),
            prepMinutes = (map["prepMinutes"] as? Number)?.toInt() ?: 20,
            featured = map["featured"] as? Boolean ?: false
        )
    }
}

/** `combos/{comboId}` — a bundle of menu items at one price. */
data class Combo(
    val id: String,
    val name: String,
    val description: String,
    val amountPaisa: Long,
    val regularAmountPaisa: Long,
    val itemRefs: List<ComboRef>,
    val imageUrl: String,
    val available: Boolean
) {

    data class ComboRef(val menuItemId: String, val qty: Int)

    val savingsPaisa: Long get() = (regularAmountPaisa - amountPaisa).coerceAtLeast(0L)

    val savingsLabel: String get() = if (savingsPaisa > 0) "Save ${Money.format(savingsPaisa)}" else ""

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase(Locale.ENGLISH)
        return name.lowercase(Locale.ENGLISH).contains(needle) || description.lowercase(Locale.ENGLISH).contains(needle)
    }

    companion object {
        fun fromDocument(id: String, map: Map<String, Any?>): Combo = Combo(
            id = id,
            name = (map["name"] as? String).orEmpty(),
            description = (map["description"] as? String).orEmpty(),
            amountPaisa = (map["amountPaisa"] as? Number)?.toLong() ?: 0L,
            regularAmountPaisa = (map["regularAmountPaisa"] as? Number)?.toLong() ?: 0L,
            itemRefs = (map["itemRefs"] as? List<*>).orEmpty().mapNotNull { entry ->
                val ref = entry as? Map<*, *> ?: return@mapNotNull null
                val itemId = ref["menuItemId"] as? String ?: return@mapNotNull null
                ComboRef(menuItemId = itemId, qty = (ref["qty"] as? Number)?.toInt() ?: 1)
            },
            imageUrl = (map["imageUrl"] as? String).orEmpty(),
            available = map["available"] as? Boolean ?: true
        )
    }
}

/**
 * One card in the store grid, whichever collection it came from.
 * Keeping item and combo in one shape means the grid, the search and the cart
 * never need to know which one they are looking at.
 */
sealed interface MenuCard {
    val id: String
    val name: String
    val amountPaisa: Long
    val imageUrl: String
    val categoryId: String
    val available: Boolean
    val searchText: String

    data class Dish(val item: MenuItem) : MenuCard {
        override val id: String get() = item.id
        override val name: String get() = item.name
        override val amountPaisa: Long get() = item.payablePaisa
        override val imageUrl: String get() = item.imageUrl
        override val categoryId: String get() = item.categoryId
        override val available: Boolean get() = item.available
        override val searchText: String get() = item.searchText()
    }

    data class Bundle(val combo: Combo) : MenuCard {
        override val id: String get() = combo.id
        override val name: String get() = combo.name
        override val amountPaisa: Long get() = combo.amountPaisa
        override val imageUrl: String get() = combo.imageUrl
        override val categoryId: String get() = COMBO_CATEGORY
        override val available: Boolean get() = combo.available
        override val searchText: String get() = combo.name.lowercase(Locale.ENGLISH) + " " + combo.description.lowercase(Locale.ENGLISH)
    }

    companion object {
        const val COMBO_CATEGORY = "combos"
    }
}

private fun MenuItem.searchText(): String =
    buildString {
        append(name.lowercase(Locale.ENGLISH))
        append(' ')
        append(description.lowercase(Locale.ENGLISH))
        append(' ')
        append(tags.joinToString(" ").lowercase(Locale.ENGLISH))
        append(' ')
        append(if (veg) "vegetarian veg" else "non vegetarian")
    }

/** Everything the menu screen needs, loaded together. */
data class MenuSnapshot(
    val categories: List<Category> = emptyList(),
    val subcategories: List<Subcategory> = emptyList(),
    val items: List<MenuItem> = emptyList(),
    val combos: List<Combo> = emptyList(),
    val campaigns: List<Campaign> = emptyList(),
    val loaded: Boolean = false
) {

    /** Only dishes the kitchen has switched on, in the vendor's own order. */
    val cards: List<MenuCard>
        get() = items.map { MenuCard.Dish(it) } + combos.map { MenuCard.Bundle(it) }

    fun categoriesWithSubcategories(): List<Category> = categories.filter { it.active }.sortedBy { it.order }

    fun subcategoriesFor(categoryId: String?): List<Subcategory> =
        subcategories
            .filter { it.active && (categoryId == null || it.categoryId == categoryId) }
            .sortedBy { it.order }

    fun dish(id: String): MenuItem? = items.firstOrNull { it.id == id }

    fun combo(id: String): Combo? = combos.firstOrNull { it.id == id }

    fun card(id: String): MenuCard? = dish(id)?.let { MenuCard.Dish(it) } ?: combo(id)?.let { MenuCard.Bundle(it) }
}

/** The sort options offered on the store screen (C-037). */
enum class MenuSort(val label: String) {
    RECOMMENDED("Recommended"),
    PRICE_LOW("Price: low to high"),
    PRICE_HIGH("Price: high to low"),
    POPULAR("Most ordered");

    fun apply(cards: List<MenuCard>, popularity: Map<String, Long>): List<MenuCard> = when (this) {
        RECOMMENDED -> cards.sortedByDescending { card ->
            if ((card as? MenuCard.Dish)?.item?.featured == true) 1 else 0
        }
        PRICE_LOW -> cards.sortedBy { it.amountPaisa }
        PRICE_HIGH -> cards.sortedByDescending { it.amountPaisa }
        POPULAR -> cards.sortedByDescending { card -> popularity[card.id] ?: 0L }
    }
}
