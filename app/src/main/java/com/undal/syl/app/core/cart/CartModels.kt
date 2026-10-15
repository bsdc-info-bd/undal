package com.undal.syl.app.core.cart
import java.util.Locale

import com.undal.syl.app.core.util.Money

/**
 * The cart (C-035 · C-042 · C-043).
 *
 * A cart line is deliberately self-contained: it carries the name and the unit
 * price it was added at, so the cart screen can render instantly, offline, before
 * any network call. The prices are re-checked against the live menu at checkout,
 * which is where being stale would actually cost somebody money.
 */
data class CartLine(
    val id: String,
    val name: String,
    val unitAmountPaisa: Long,
    val quantity: Int,
    val note: String = "",
    val imageUrl: String = "",
    val veg: Boolean = false,
    val spiceLevel: Int = 0,
    val isCombo: Boolean = false
) {

    val lineTotalPaisa: Long get() = Money.lineTotal(unitAmountPaisa, quantity)
}

data class Cart(
    val lines: List<CartLine> = emptyList(),
    val couponCode: String = "",
    val updatedAtMillis: Long = 0L
) {

    val itemCount: Int get() = lines.sumOf { it.quantity }

    val distinctCount: Int get() = lines.size

    val isEmpty: Boolean get() = lines.isEmpty()

    val subtotalPaisa: Long get() = lines.sumOf { it.lineTotalPaisa }

    /**
     * Only the same dish with the same note merges: two plates of kacchi with
     * different spice notes are two kitchens instructions, not one.
     */
    fun withLine(line: CartLine): Cart {
        val existing = lines.firstOrNull { it.id == line.id && it.note == line.note }
        val next = if (existing == null) {
            lines + line.copy(quantity = line.quantity.coerceIn(1, MAX_QUANTITY_PER_LINE))
        } else {
            lines.map { current ->
                if (current === existing) {
                    current.copy(
                        quantity = (current.quantity + line.quantity).coerceAtMost(MAX_QUANTITY_PER_LINE)
                    )
                } else {
                    current
                }
            }
        }
        return copy(lines = next, updatedAtMillis = System.currentTimeMillis())
    }

    fun withQuantity(lineId: String, note: String, quantity: Int): Cart {
        if (quantity <= 0) return without(lineId, note)
        val next = lines.map { line ->
            if (line.id == lineId && line.note == note) {
                line.copy(quantity = quantity.coerceAtMost(MAX_QUANTITY_PER_LINE))
            } else {
                line
            }
        }
        return copy(lines = next, updatedAtMillis = System.currentTimeMillis())
    }

    fun without(lineId: String, note: String): Cart {
        val next = lines.filterNot { it.id == lineId && it.note == note }
        return copy(lines = next, updatedAtMillis = System.currentTimeMillis())
    }

    fun quantityOf(lineId: String): Int = lines.filter { it.id == lineId }.sumOf { it.quantity }

    fun withCoupon(code: String): Cart = copy(
        couponCode = code.trim().uppercase(Locale.ENGLISH),
        updatedAtMillis = System.currentTimeMillis()
    )

    fun cleared(): Cart = Cart()

    companion object {
        /** Matches the rules' `qty <= 50` per line and 40 lines per order. */
        const val MAX_QUANTITY_PER_LINE = 50
        const val MAX_LINES = 40
    }
}

/**
 * The cart as a JSON string so it survives process death without a database.
 * Written by hand rather than with a serialization library: the shape is five
 * fields, and a hand-written parser cannot be broken by a dependency upgrade.
 */
object CartCodec {

    fun encode(cart: Cart): String {
        val root = org.json.JSONObject()
        root.put("coupon", cart.couponCode)
        root.put("updatedAt", cart.updatedAtMillis)
        val array = org.json.JSONArray()
        cart.lines.forEach { line ->
            array.put(
                org.json.JSONObject().apply {
                    put("id", line.id)
                    put("name", line.name)
                    put("unit", line.unitAmountPaisa)
                    put("qty", line.quantity)
                    put("note", line.note)
                    put("image", line.imageUrl)
                    put("veg", line.veg)
                    put("spice", line.spiceLevel)
                    put("combo", line.isCombo)
                }
            )
        }
        root.put("lines", array)
        return root.toString()
    }

    fun decode(raw: String?): Cart {
        if (raw.isNullOrBlank()) return Cart()
        return runCatching {
            val root = org.json.JSONObject(raw)
            val array = root.optJSONArray("lines") ?: org.json.JSONArray()
            val lines = (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CartLine(
                    id = id,
                    name = entry.optString("name"),
                    unitAmountPaisa = entry.optLong("unit"),
                    quantity = entry.optInt("qty").coerceIn(1, Cart.MAX_QUANTITY_PER_LINE),
                    note = entry.optString("note"),
                    imageUrl = entry.optString("image"),
                    veg = entry.optBoolean("veg"),
                    spiceLevel = entry.optInt("spice"),
                    isCombo = entry.optBoolean("combo")
                )
            }
            Cart(
                lines = lines.take(Cart.MAX_LINES),
                couponCode = root.optString("coupon"),
                updatedAtMillis = root.optLong("updatedAt")
            )
        }.getOrDefault(Cart())
    }
}
