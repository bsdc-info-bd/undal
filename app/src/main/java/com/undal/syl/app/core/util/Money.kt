package com.undal.syl.app.core.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Money in Undal is never a floating-point number.
 *
 * Every amount is a `Long` count of paisa (100 paisa = ৳1). Paisa arithmetic is
 * exact integer arithmetic, so the totals a customer sees, the totals stored in
 * Firestore and the totals the security rules validate are the same number
 * forever. Floats are used only for distances, and only to compute a km count
 * that is then rounded up before it is multiplied by a paisa rate.
 */
object Money {

    const val PAISA_PER_TAKA: Int = 100

    /** ৳1,250 · ৳1,250.50 — grouped in the Indian/Bangladeshi lakh pattern. */
    fun format(amountPaisa: Long): String {
        val taka = amountPaisa / PAISA_PER_TAKA
        val paisa = (amountPaisa % PAISA_PER_TAKA).toInt()
        val whole = groupLakh(taka.toString())
        return if (paisa == 0) {
            "৳ $whole"
        } else {
            val fraction = paisa.toString().padStart(2, '0').trimEnd('0')
            "৳ $whole.$fraction"
        }
    }

    /**
     * `৳ 1,00,000`, never `৳ 100,000`: the last three digits stay together and
     * everything left of them is counted off in pairs.
     *
     * The grouping is worked out here instead of being asked of a locale. This
     * is a money rule, not a regional preference, and a runtime's locale data is
     * not a contract: `NumberFormat.getNumberInstance(Locale("en", "IN"))`
     * answered `৳ 100,000` in CI while the same call renders the lakh pattern on
     * a phone. The Worker's `groupIndian` in `worker/src/util/money.ts` is the
     * same algorithm by hand for the same reason, so the two can never disagree
     * about a total a customer is reading.
     *
     * 1250 → "1,250" · 100000 → "1,00,000" · 12345678 → "1,23,45,678".
     */
    private fun groupLakh(digits: String): String {
        val negative = digits.startsWith("-")
        val body = if (negative) digits.substring(1) else digits
        val grouped = if (body.length <= 3) {
            body
        } else {
            val head = body.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
            "$head,${body.takeLast(3)}"
        }
        return if (negative) "-$grouped" else grouped
    }

    /** Plain number without the symbol, for CSV export and share text. */
    fun formatPlain(amountPaisa: Long): String =
        BigDecimal(amountPaisa).divide(BigDecimal(PAISA_PER_TAKA), 2, RoundingMode.HALF_UP).toPlainString()

    /** Parses vendor input ("250", "250.50", "৳ 250") into paisa, or null if invalid. */
    fun parseTakaToPaisa(raw: String): Long? {
        val cleaned = raw.replace("৳", "").replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        val decimal = cleaned.toBigDecimalOrNull() ?: return null
        if (decimal.signum() < 0) return null
        return decimal.multiply(BigDecimal(PAISA_PER_TAKA)).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** The single rounding rule of the platform: bill whole kilometres, rounded up. */
    fun deliveryFeePaisa(distanceKm: Double, perKmPaisa: Long): Long {
        if (distanceKm.isNaN() || distanceKm <= 0.0) return 0L
        val billedKm = kotlin.math.ceil(distanceKm).toLong().coerceAtLeast(1L)
        return billedKm * perKmPaisa
    }

    /** Percentage discount on an amount, floored to whole paisa. */
    fun percentDiscount(amountPaisa: Long, percent: Int): Long {
        val safePercent = percent.coerceIn(0, 100)
        return amountPaisa * safePercent / 100
    }

    /** Cart line total; qty is validated >= 1 before it reaches here. */
    fun lineTotal(unitAmountPaisa: Long, quantity: Int): Long =
        unitAmountPaisa * quantity.coerceAtLeast(0).toLong()

    /**
     * Grand total, clamped so a discount can never produce a negative charge.
     * The security rules recompute the same expression on write.
     */
    fun grandTotal(subtotalPaisa: Long, deliveryPaisa: Long, discountPaisa: Long): Long {
        val raw = subtotalPaisa + deliveryPaisa - discountPaisa
        return raw.coerceAtLeast(0L)
    }

    /** Effective discount for a discount-capable coupon, never more than the subtotal. */
    fun applicableDiscount(
        subtotalPaisa: Long,
        type: String,
        value: Long
    ): Long = when (type.lowercase(Locale.ROOT)) {
        "percent" -> percentDiscount(subtotalPaisa, value.toInt()).coerceAtMost(subtotalPaisa)
        "fixed" -> value.coerceAtMost(subtotalPaisa)
        else -> 0L
    }
}
