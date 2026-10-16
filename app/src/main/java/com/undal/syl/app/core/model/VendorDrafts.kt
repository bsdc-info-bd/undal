package com.undal.syl.app.core.model
import java.util.Locale

import com.google.firebase.Timestamp
import com.undal.syl.app.core.util.CouponMath
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.UndalDigits
import com.undal.syl.app.core.util.Validators

/**
 * What the kitchen types into the editor screens (V-049 … V-070).
 *
 * Every draft knows whether it is valid and why it is not, so the owner is told
 * "a price must be more than zero" instead of watching a save button stay grey.
 */

/**
 * The ceiling the security rules put on a dish: `amountPaisa <= 1000000` in
 * `firebase/firestore.rules`, in paisa, so ৳10,000. It is written here as a name
 * because two fields are measured against it and the rules measure only the one
 * they can see — and a limit that lives in a literal in one `when` branch drifts.
 */
const val MAX_ITEM_PAISA: Long = 1_000_000L

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
            .map { it.trim().lowercase(Locale.ENGLISH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)

    /**
     * The dish name, judged by the same floor the security rules enforce — three
     * characters, because `nonBlankString()` in `firebase/firestore.rules` is
     * `size() >= 3`. Accepting two here meant the save was refused by the database
     * with nothing but "Missing or insufficient permissions", which reads as a
     * broken menu editor rather than a short name.
     */
    val nameError: String? get() = Validators.menuName("dish name", name)

    val priceError: String?
        get() = when {
            priceText.isBlank() -> "Set what the customer pays."
            pricePaisa == null -> "That price is not a number."
            (pricePaisa ?: 0L) <= 0L -> "A price must be more than zero."
            (pricePaisa ?: 0L) > MAX_ITEM_PAISA -> "The maximum item price is ${Money.format(MAX_ITEM_PAISA)}."
            else -> null
        }

    /**
     * The compare price is capped by the same ceiling, because it is not a second
     * number: `amountPaisa` is whichever of the two is larger, and that is the
     * field the rules put a ceiling on. An editor that capped only the price let a
     * dish through with a ৳15,000 "was" price and a ৳500 "now" price, and the
     * database refused the save with nothing but "Missing or insufficient
     * permissions" — which reads as a broken menu editor, not a price too high.
     */
    val compareError: String?
        get() = when {
            compareText.isBlank() -> null
            comparePaisa == null -> "That compare price is not a number."
            (comparePaisa ?: 0L) < (pricePaisa ?: 0L) -> "The compare price must be at least the price."
            (comparePaisa ?: 0L) > MAX_ITEM_PAISA ->
                "The maximum price is ${Money.format(MAX_ITEM_PAISA)}, and the compare price is stored as " +
                    "this dish's list price."
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
        get() = Validators.menuName("combo name", name)
            ?: when {
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
        get() = if (type == "percent") UndalDigits.toLongOrNull(valueText) else Money.parseTakaToPaisa(valueText)

    val valueLabel: String
        get() = when {
            type == "percent" -> "${value ?: 0L} % off"
            else -> "${Money.format(value ?: 0L)} off"
        }

    val error: String?
        get() = Validators.menuName("campaign title", title)
            ?: when {
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
    val active: Boolean = true,
    /** Ceiling on a percentage or a free-delivery coupon, in taka text. */
    val maxDiscountText: String = "",
    /** "HH:MM" — the daily window, in Asia/Dhaka. A window may cross midnight. */
    val startsAt: String = "",
    val endsAt: String = "",
    /** ["fri", "sat"] — empty means every day. */
    val days: Set<String> = emptySet(),
    /** Applied by every basket it helps, with no code typed. */
    val autoApply: Boolean = true,
    /** Shown as a banner on the web store and in the app. */
    val showOnWeb: Boolean = true
) {

    val normalisedCode: String get() = code.trim().uppercase(Locale.ENGLISH).replace(' ', '-')

    val codeError: String?
        get() = if (CODE_PATTERN.matches(normalisedCode)) {
            null
        } else {
            "Codes are 3–24 letters, digits, dashes or underscores."
        }

    val value: Long?
        get() = if (type == "percent") UndalDigits.toLongOrNull(valueText) else Money.parseTakaToPaisa(valueText)

    val valueLabel: String
        get() = when {
            type == "percent" -> "${value ?: 0L} % off"
            else -> "${Money.format(value ?: 0L)} off"
        }

    val valueError: String?
        get() = when {
            type == "percent" && (value ?: 0L) !in 1..90 -> "A percentage must be between 1 and 90."
            type == "fixed" && (value ?: 0L) <= 0L -> "A fixed discount must be more than zero."
            // Free delivery needs no value: it is worth the ride, whatever the ride is.
            type == "freedelivery" -> null
            else -> null
        }

    /** The ceiling in paisa, or 0 when the coupon is not capped. */
    val maxDiscountPaisa: Long
        get() = if (maxDiscountText.isBlank()) 0L else Money.parseTakaToPaisa(maxDiscountText) ?: -1L

    val maxDiscountError: String?
        get() = when {
            maxDiscountText.isBlank() -> null
            maxDiscountPaisa <= 0L -> "Write the cap in taka, like 100."
            type == "fixed" -> "A fixed-amount coupon is already exact — remove the cap."
            else -> null
        }

    /**
     * A date that has already passed is refused here, in the editor, rather than
     * at somebody's checkout.
     *
     * This is the mistake behind "the coupon is active but the web says Expired":
     * the switch is on, the code reads as live in the list, and every customer who
     * types it is refused. The owner cannot see that from a toggle — but they can
     * see it from a sentence written at the moment they pick the date.
     *
     * An empty expiry is not an error. A promotion with no end date is a decision.
     */
    val expiryError: String?
        get() = expiresAtMillis?.takeIf { it <= System.currentTimeMillis() }?.let {
            "That date has already passed — pick today or later, or leave the expiry empty for no end date."
        }

    /** "HH:MM" or blank, both ends. A window that ends before it starts is fine. */
    val windowError: String?
        get() = when {
            startsAt.isNotBlank() && CouponMath.parseMinutesOfDay(startsAt) == null ->
                "Start the window as HH:MM, like 18:00."
            endsAt.isNotBlank() && CouponMath.parseMinutesOfDay(endsAt) == null ->
                "End the window as HH:MM, like 22:00 or 01:00 after midnight."
            else -> null
        }

    /** The coupon this draft describes, for a preview that cannot lie. */
    fun toCoupon(active: Boolean = this.active): CouponMath.Coupon = CouponMath.Coupon(
        code = normalisedCode,
        type = type,
        value = value ?: 0L,
        active = active,
        description = description.trim(),
        minSubtotalAmountPaisa = minOrderPaisa,
        maxDiscountPaisa = maxDiscountPaisa.coerceAtLeast(0L),
        expiresAtMillis = expiresAtMillis ?: 0L,
        startsAt = startsAt.trim(),
        endsAt = endsAt.trim(),
        daysOfWeek = days.toList().sorted(),
        usageLimit = usageLimit,
        autoApply = autoApply,
        showOnWeb = showOnWeb
    )

    /**
     * What this coupon would be worth on a typical basket, worked out by the same
     * rule the customer's phone and the Worker run — so the kitchen sees the
     * discount it is about to give away before it gives it away.
     */
    fun preview(sample: SampleBasket = SampleBasket()): String {
        val coupon = toCoupon(active = true)
        val verdict = CouponMath.evaluate(
            coupon,
            CouponMath.Context(sample.subtotalPaisa, sample.deliveryPaisa)
        )
        val basket = "on a ${Money.format(sample.subtotalPaisa)} basket with " +
            "${Money.format(sample.deliveryPaisa)} delivery"
        return if (verdict.eligible) {
            "${coupon.headline} — ${Money.format(verdict.discountPaisa)} off $basket."
        } else {
            "Nothing off $basket (${verdict.reason})."
        }
    }

    /** The basket a preview is quoted against. */
    data class SampleBasket(
        val subtotalPaisa: Long = 50_000L,
        val deliveryPaisa: Long = 5_000L
    )

    val minOrderPaisa: Long get() = Money.parseTakaToPaisa(minOrderText) ?: 0L

    /**
     * 0 means unlimited, so a value the app cannot read must not quietly mean
     * "no limit at all": ৫০ typed on a Bangla keyboard is 50 uses, and before
     * this it became 0 — a coupon with no ceiling, which is the precise mistake
     * the field exists to prevent.
     */
    val usageLimit: Long get() = UndalDigits.toLongOrNull(usageLimitText)?.coerceIn(0L, 100_000L) ?: 0L

    val isValid: Boolean
        get() = firstError == null

    /**
     * The first thing wrong with this draft, in the order the fields appear on the
     * sheet — and the only way the save button may refuse.
     *
     * Every caller used to chain two of the five errors by hand
     * (`codeError ?: valueError`), so a draft whose *window* or *expiry* was wrong
     * produced no sentence at all: the button did nothing and the owner had no idea
     * why. One list, in one place, means a refusal always says what to fix.
     */
    val firstError: String?
        get() = codeError
            ?: valueError
            ?: maxDiscountError
            ?: windowError
            ?: expiryError

    /**
     * The document the customer's phone, the Worker and the web store all read.
     * `maxDiscountPaisa: 0` is written as 0 rather than omitted, because "no cap"
     * is a decision and not a missing field.
     */
    fun writeFields(): Map<String, Any?> = mapOf(
        "type" to type,
        "value" to (value ?: 0L),
        "active" to active,
        "minSubtotalAmountPaisa" to minOrderPaisa,
        "maxDiscountPaisa" to maxDiscountPaisa.coerceAtLeast(0L),
        "startsAt" to startsAt.trim(),
        "endsAt" to endsAt.trim(),
        "daysOfWeek" to days.toList().sorted(),
        "autoApply" to autoApply,
        "showOnWeb" to showOnWeb,
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
            active = coupon.active,
            maxDiscountText = if (coupon.maxDiscountPaisa > 0L) Money.formatPlain(coupon.maxDiscountPaisa) else "",
            startsAt = coupon.startsAt,
            endsAt = coupon.endsAt,
            days = coupon.daysOfWeek.toSet(),
            autoApply = coupon.autoApply,
            showOnWeb = coupon.showOnWeb
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
    val description: String,
    val maxDiscountPaisa: Long = 0L,
    val startsAt: String = "",
    val endsAt: String = "",
    val daysOfWeek: List<String> = emptyList(),
    val autoApply: Boolean = true,
    val showOnWeb: Boolean = true
) {

    /** The shared rule's shape, so the kitchen's list and the cart agree. */
    val math: CouponMath.Coupon get() = CouponMath.Coupon(
        code = code,
        type = type,
        value = value,
        active = active,
        description = description,
        minSubtotalAmountPaisa = minSubtotalAmountPaisa,
        maxDiscountPaisa = maxDiscountPaisa,
        expiresAtMillis = expiresAtMillis ?: 0L,
        startsAt = startsAt,
        endsAt = endsAt,
        daysOfWeek = daysOfWeek,
        usageLimit = usageLimit,
        usedCount = usedCount,
        autoApply = autoApply,
        showOnWeb = showOnWeb
    )

    /** "20% off, up to ৳ 100" · "৳ 50 off" · "Free delivery". */
    val headline: String get() = math.headline

    /** "On orders over ৳ 250 · 18:00–22:00 · fri". */
    val conditionLabel: String get() = CouponMath.conditionLine(math)

    /** How the coupon reaches people: automatically, by code, or on the web only. */
    val reachLabel: String
        get() = when {
            autoApply && showOnWeb -> "Applies itself · shown on the web store"
            autoApply -> "Applies itself · hidden from the web store"
            showOnWeb -> "Code only · shown on the web store"
            else -> "Code only · hidden from the web store"
        }

    val valueLabel: String
        get() = if (type == "percent") "$value % off" else "${Money.format(value)} off"

    val usedLabel: String
        get() = if (usageLimit <= 0L) "$usedCount used · no limit" else "$usedCount of $usageLimit used"

    val expired: Boolean
        get() = expiresAtMillis != null && expiresAtMillis < System.currentTimeMillis()

    /** Switched on, and its usage allowance already spent. */
    val usedUp: Boolean
        get() = usageLimit > 0L && usedCount >= usageLimit

    /**
     * Switched on but able to give nobody anything — the state the toggle cannot
     * show. This is the confusion worth designing out: the kitchen sees the switch
     * in the on position, tells a customer the code, and the checkout refuses it.
     */
    val onButDead: Boolean
        get() = active && (expired || usedUp)

    /**
     * The one line that answers "is this coupon doing anything right now?", with
     * the date in it. A switch says what the owner *asked for*; this says what a
     * customer will actually get.
     */
    val stateLabel: String
        get() = when {
            !active -> "Switched off · nobody gets it"
            expired ->
                if (expiryLabel.isBlank()) "Expired · not running"
                else "Expired on $expiryLabel · not running"
            usedUp -> "All $usageLimit uses spent · not running"
            expiryLabel.isBlank() -> "Live · no end date"
            else -> "Live · ends $expiryLabel"
        }

    /** "31 Oct 2026, 11:59 PM" — the minute customers stop getting it, as the checkout prints it. */
    val expiryLabel: String
        get() = expiresAtMillis?.let { CouponMath.expiryMoment(it) }.orEmpty()

    companion object {
        fun fromDocument(id: String, map: Map<String, Any?>): VendorCoupon = VendorCoupon(
            code = id,
            type = (map["type"] as? String).orEmpty(),
            value = (map["value"] as? Number)?.toLong() ?: 0L,
            active = map["active"] as? Boolean ?: false,
            minSubtotalAmountPaisa = (map["minSubtotalAmountPaisa"] as? Number)?.toLong() ?: 0L,
            usageLimit = (map["usageLimit"] as? Number)?.toLong() ?: 0L,
            usedCount = (map["usedCount"] as? Number)?.toLong() ?: 0L,
            // Every shape the field can arrive in, through the shared rule's own
            // reader: a Timestamp from the SDK, a Date, millis, an ISO string or a
            // `{seconds, nanos}` map. Reading only `as? Timestamp` answered `null`
            // — "no expiry" — for the other four.
            expiresAtMillis = CouponMath.expiryMillis(map["expiresAt"] ?: map["expiresAtMillis"])
                .takeIf { it > 0L },
            description = (map["description"] as? String).orEmpty(),
            maxDiscountPaisa = (map["maxDiscountPaisa"] as? Number)?.toLong() ?: 0L,
            startsAt = (map["startsAt"] as? String).orEmpty(),
            endsAt = (map["endsAt"] as? String).orEmpty(),
            daysOfWeek = CouponMath.days(map["daysOfWeek"]),
            // Both defaults are opt-out, and they are the same in three places:
            // coupons.ts, cart-client.ts and here. A coupon the kitchen created is
            // meant to be used.
            autoApply = map["autoApply"] != false,
            showOnWeb = map["showOnWeb"] != false
        )
    }
}
