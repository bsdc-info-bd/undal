package com.undal.syl.app.core.model

/**
 * The menu, counted the way a kitchen actually worries about it.
 *
 * These numbers exist for one screen — the menu manager's health card — and they
 * are computed here, outside the ViewModel, so they can be tested without a
 * Firestore listener, a coroutine scope or a LiveData. A dish is "not ready" for
 * exactly three reasons worth telling an owner about: it is off sale, it has no
 * photo, or it has no prep time. Everything else about a dish is taste.
 */
data class MenuHealth(
    val dishes: Int = 0,
    val onSale: Int = 0,
    val soldOut: Int = 0,
    val missingPhoto: Int = 0,
    val missingPrep: Int = 0,
    val unfeatured: Int = 0,
    val unpriced: Int = 0
) {
    /**
     * Ready for service means: something to sell, everything that is on sale has a
     * photo (a customer scrolling a grid of grey boxes does not order), and every
     * dish says how long it takes (the prep promise is what the countdown counts).
     * A sold-out dish is not a problem — it is a fact of a kitchen — so it never
     * makes the menu unhealthy.
     */
    val isHealthy: Boolean
        get() = dishes > 0 && onSale > 0 && missingPhoto == 0 && missingPrep == 0 && unpriced == 0

    /** Everything wrong, in the order an owner should fix it. */
    val problems: List<String>
        get() = buildList {
            if (dishes == 0) add("No dishes on the menu yet")
            if (dishes > 0 && onSale == 0) add("Every dish is sold out — customers see an empty shop")
            if (unpriced > 0) add("${plural(unpriced, "dish has", "dishes have")} no price")
            if (missingPhoto > 0) add("${plural(missingPhoto, "dish has", "dishes have")} no photo")
            if (missingPrep > 0) add("${plural(missingPrep, "dish has", "dishes have")} no prep time")
        }

    /** "3 dishes have" / "1 dish has" — the number belongs in the sentence. */
    private fun plural(count: Int, one: String, many: String): String =
        if (count == 1) "1 $one" else "$count $many"
}

object MenuAudit {

    /** Counts [items] into the one summary the menu manager shows. */
    fun of(items: List<MenuItem>): MenuHealth = MenuHealth(
        dishes = items.size,
        onSale = items.count { it.available },
        soldOut = items.count { !it.available },
        missingPhoto = items.count { it.imageUrl.isBlank() },
        missingPrep = items.count { it.prepMinutes <= 0 },
        unfeatured = items.count { !it.featured },
        unpriced = items.count { it.payablePaisa <= 0L }
    )
}
