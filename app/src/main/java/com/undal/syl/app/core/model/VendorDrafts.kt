package com.undal.syl.app.core.model

import com.google.firebase.Timestamp
import com.undal.syl.app.core.util.Money

/**
 * What the kitchen types into the editor screens (V-049 … V-070).
 *
 * Every draft knows whether it is valid and why it is not, so the owner is told
 * "a price must be more than zero" instead of watching a save button stay grey.
 */

data class MenuDraft(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val priceText: String = "",
    val compareText: String = "",
    val veg: Boolean = false,
    val spiceLevel: Int = 0,
    val tagsText: String = "",
    val categoryId: String = "",
    val subcategoryId: String = "",
    val imageUrl: String = "",
    val available: Boolean = true,
    val featured: Boolean = false,
    val prepMinutes: Int = 20
) {

    val isNew: Boolean get() = id == null

    val pricePaisa: Long? get() = Money.parseTakaToPaisa(priceText)

    val comparePaisa: Long? get() = Money.parseTakaToPaisa(compareText)

    /**
     * `menuItems.amountPaisa` is the list price the app strikes through, and
     * `discountAmountPaisa` is the saving. A dish with no compare price simply
     * has amount == price and a zero discount, which is how the customer app
     * decides whether to draw the strike-through at all.
     */
    val amountPaisa: Long get() = maxOf(comparePaisa ?: 0L, pricePaisa ?: 0L)

    val discountAmountPaisa: Long get() = (amountPaisa - (pricePaisa ?: 0L)).coerceAtLeast(0L)

    val tags: List<String>
        get() = tagsText.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)

    val nameError: String?
        get() = when {
            name.trim().isEmpty() -> "Give the dish a name."
            name.trim().length < 2 -> "That name is too short."
            else -> null
        }

    val priceError: String?
        get() = when {
            priceText.isBlank() -> "Set what the customer pays."
            pricePaisa == null -> "That price is not a number."
            (pricePaisa ?: 0L) <= 0L -> "A price must be more than zero."
            (pricePaisa ?: 0L) > 1_000_000L -> "The maximum item price is ৳ 10,000."
            else -> null
        }

    val compareError: String?
        get() = when {
            compareText.isBlank() -> null
            comparePaisa == null -> "That compare price is not a number."
            (comparePaisa ?: 0L) < (pricePaisa ?: 0L) -> "The compare price must be at least the price."
            else -> null
        }

    val categoryError: String? get() = if (categoryId.isBlank()) "Pick a category." else null

    val isValid: Boolean
        get() = nameError == null && priceError == null && compareError == null && categoryError == null

    /** The fields both create and update write. The repo adds the timestamps. */
    fun writeFields(): Map<String, Any?> = mapOf(
        "name" to name.trim(),
        "description" to description.trim().take(400),
        "amountPaisa" to amountPaisa,
        "discountAmountPaisa" to discountAmountPaisa,
        "available" to available,
        "veg" to veg,
        "spiceLevel" to spiceLevel.coerceIn(0, 3),
        "tags" to tags,
        "categoryId" to categoryId,
        "subcategoryId" to subcategoryId,
        "imageUrl" to imageUrl,
        "prepMinutes" to prepMinutes.coerceIn(5, 180),
        "featured" to featured
    )

    /** True when nothing has changed, so the editor can warn before leaving (V-054). */
    fun sameAs(item: MenuItem): Boolean =
        name.trim() == item.name &&
            description.trim() == item.description &&
            amountPaisa == item.amountPaisa &&
            discountAmountPaisa == item.discountAmountPaisa &&
            veg == item.veg &&
            spiceLevel.coerceIn(0, 3) == item.spiceLevel &&
            tags == item.tags &&
            categoryId == item.categoryId &&
            subcategoryId == item.subcategoryId &&
            imageUrl == item.imageUrl &&
            available == item.available &&
            featured == item.featured &&
            prepMinutes.coerceIn(5, 180) == item.prepMinutes

    companion object {
        fun of(item: MenuItem): MenuDraft {
            val price = item.payablePaisa
            val compare = if (item.hasDiscount) item.amountPaisa else 0L
            return MenuDraft(
                id = item.id,
                name = item.name,
                description = item.description,
                priceText = Money.formatPlain(price),
                compareText = if (compare > 0L) Money.formatPlain(compare) else "",
                veg = item.veg,
                spiceLevel = item.spiceLevel,
                tagsText = item.tags.joinToString(", "),
                categoryId = item.categoryId,
                subcategoryId = item.subcategoryId,
                imageUrl = item.imageUrl,
                available = item.available,
                featured = item.featured,
                prepMinutes = item.prepMinutes
            )
        }
    }
}

data class ComboRefDraft(
    val menuItemId: String,
    val qty: Int = 1,
    val name: String = "",
    val unitAmountPaisa: Long = 0L
) {
    val lineTotalPaisa: Long get() = unitAmountPaisa * qty
}

data class ComboDraft(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val priceText: String = "",
    val imageUrl: String = "",
    val available: Boolean = true,
    val refs: List<ComboRefDraft> = emptyList()
) {

    val pricePaisa: Long? get() = Money.parseTakaToPaisa(priceText)

    /** What the same food costs ordered one dish at a time — the saving line (V-059). */
    val regularPaisa: Long get() = refs.sumOf { it.lineTotalPaisa }

    val savingsPaisa: Long get() = (regularPaisa - (pricePaisa ?: 0L)).coerceAtLeast(0L)

    val savingsLabel: String
        get() = if (savingsPaisa > 0L) "Customers save ${Money.format(savingsPaisa)}" else "No saving yet"

    val error: String?
        get() = when {
            name.trim().length < 2 -> "Name the combo."
            refs.size < 2 -> "A combo needs at least two dishes."
            refs.any { it.qty < 1 } -> "Every dish in a combo needs a quantity."
            pricePaisa == null -> "Set the combo price."
            (pricePaisa ?: 0L) <= 0L -> "The combo price must be more than zero."
            (pricePaisa ?: 0L) > regularPaisa -> "The combo cannot cost more than ordering separately."
            else -> null
        }

    val isValid: Boolean get() = error == null

    companion object {
        fun of(combo: Combo, items: List<MenuItem>): ComboDraft = ComboDraft(
            id = combo.id,
            name = combo.name,
            description = combo.description,
            priceText = Money.formatPlain(combo.amountPaisa),
            imageUrl = combo.imageUrl,
            available = combo.available,
            refs = combo.itemRefs.map { ref ->
                val item = items.firstOrNull { it.id == ref.menuItemId }
                ComboRefDraft(
                    menuItemId = ref.menuItemId,
                    qty = ref.qty,
                    name = item?.name.orEmpty(),
                    unitAmountPaisa = item?.payablePaisa ?: 0L
                )
            }
        )
    }
}

data class CampaignDraft(
    val id: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val type: String = "percent",
    val valueText: String = "",
    val startAtMillis: Long? = null,
    val endAtMillis: Long? = null,
    val bannerUrl: String = "",
    val code: String = "",
    val active: Boolean = true
) {

    /** Percent campaigns store the number; fixed campaigns store paisa. */
    val value: Long?
        get() = if (type == "percent") valueText.trim().toLongOrNull() else Money.parseTakaToPaisa(valueText)

    val valueLabel: String
        get() = when {
            type == "percent" -> "${value ?: 0L} % off"
            else -> "${Money.format(value ?: 0L)} off"
        }

    val error: String?
        get() = when {
            title.trim().isEmpty() -> "Give the campaign a title."
            type == "percent" && (value ?: 0L) !in 1..90 -> "A percentage must be between 1 and 90."
            type == "fixed" && (value ?: 0L) <= 0L -> "A fixed discount must be more than zero."
            startAtMillis == null -> "Pick the day it starts."
            endAtMillis == null -> "Pick the day it ends."
            (startAtMillis ?: 0L) >= (endAtMillis ?: 0L) -> "The end must come after the start."
            else -> null
        }

    val isValid: Boolean get() = error == null

    companion object {
        fun of(campaign: Campaign): CampaignDraft = CampaignDraft(
            id = campaign.id,
            title = campaign.title,
            subtitle = campaign.subtitle,
            type = campaign.type,
            valueText = if (campaign.type == "percent") {
                campaign.value.toString()
            } else {
                Money.formatPlain(campaign.value)
            },
            startAtMillis = campaign.startAt?.toDate()?.time,
            endAtMillis = campaign.endAt?.toDate()?.time,
            bannerUrl = campaign.bannerUrl,
            code = campaign.code
        )
    }
}

data class CouponDraft(
    val originalCode: String? = null,
    val code: String = "",
    val type: String = "percent",
    val valueText: String = "",
    val minOrderText: String = "",
    val usageLimitText: String = "",
    val expiresAtMillis: Long? = null,
    val description: String = "",
    val active: Boolean = true
) {

    val normalisedCode: String get() = code.trim().uppercase().replace(' ', '-')

    val codeError: String?
        get() = if (CODE_PATTERN.matches(normalisedCode)) {
            null
        } else {
            "Codes are 3–24 letters, digits, dashes or underscores."
        }

    val value: Long?
        get() = if (type == "percent") valueText.trim().toLongOrNull() else Money.parseTakaToPaisa(valueText)

    val valueLabel: String
        get() = when {
            type == "percent" -> "${value ?: 0L} % off"
            else -> "${Money.format(value ?: 0L)} off"
        }

    val valueError: String?
        get() = when {
            type == "percent" && (value ?: 0L) !in 1..90 -> "A percentage must be between 1 and 90."
            type == "fixed" && (value ?: 0L) <= 0L -> "A fixed discount must be more than zero."
            else -> null
        }

    val minOrderPaisa: Long get() = Money.parseTakaToPaisa(minOrderText) ?: 0L

    val usageLimit: Long get() = usageLimitText.trim().toLongOrNull()?.coerceIn(0L, 100_000L) ?: 0L

    val isValid: Boolean get() = codeError == null && valueError == null

    fun writeFields(): Map<String, Any?> = mapOf(
        "type" to type,
        "value" to (value ?: 0L),
        "active" to active,
        "minSubtotalAmountPaisa" to minOrderPaisa,
        "usageLimit" to usageLimit,
        "usedCount" to 0L,
        "expiresAt" to expiresAtMillis?.let { Timestamp(java.util.Date(it)) },
        "description" to description.trim().take(120)
    )

    companion object {
        private val CODE_PATTERN = Regex("^[A-Z0-9_-]{3,24}$")

        fun of(coupon: VendorCoupon): CouponDraft = CouponDraft(
            originalCode = coupon.code,
            code = coupon.code,
            type = coupon.type,
            valueText = if (coupon.type == "percent") {
                coupon.value.toString()
            } else {
                Money.formatPlain(coupon.value)
            },
            minOrderText = if (coupon.minSubtotalAmountPaisa > 0L) {
                Money.formatPlain(coupon.minSubtotalAmountPaisa)
            } else {
                ""
            },
            usageLimitText = if (coupon.usageLimit > 0L) coupon.usageLimit.toString() else "",
            expiresAtMillis = coupon.expiresAtMillis,
            description = coupon.description,
            active = coupon.active
        )
    }
}

/** `coupons/{CODE}` as the vendor manager lists it (V-063 … V-065). */
data class VendorCoupon(
    val code: String,
    val type: String,
    val value: Long,
    val active: Boolean,
    val minSubtotalAmountPaisa: Long,
    val usageLimit: Long,
    val usedCount: Long,
    val expiresAtMillis: Long?,
    val description: String
) {

    val valueLabel: String
        get() = if (type == "percent") "$value % off" else "${Money.format(value)} off"

    val usedLabel: String
        get() = if (usageLimit <= 0L) "$usedCount used · no limit" else "$usedCount of $usageLimit used"

    val expired: Boolean
        get() = expiresAtMillis != null && expiresAtMillis < System.currentTimeMillis()

    val expiryLabel: String
        get() = expiresAtMillis
            ?.let { com.undal.syl.app.core.util.TimeDhaka.formatDate(
                com.undal.syl.app.core.util.TimeDhaka
                    .fromInstant(java.time.Instant.ofEpochMilli(it)).toLocalDate()
            ) }
            .orEmpty()

    companion object {
        fun fromDocument(id: String, map: Map<String, Any?>): VendorCoupon = VendorCoupon(
            code = id,
            type = (map["type"] as? String).orEmpty(),
            value = (map["value"] as? Number)?.toLong() ?: 0L,
            active = map["active"] as? Boolean ?: false,
            minSubtotalAmountPaisa = (map["minSubtotalAmountPaisa"] as? Number)?.toLong() ?: 0L,
            usageLimit = (map["usageLimit"] as? Number)?.toLong() ?: 0L,
            usedCount = (map["usedCount"] as? Number)?.toLong() ?: 0L,
            expiresAtMillis = (map["expiresAt"] as? Timestamp)?.toDate()?.time,
            description = (map["description"] as? String).orEmpty()
        )
    }
}
