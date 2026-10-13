package com.undal.syl.app.data.repo

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.util.Money
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coupon validation (C-052 · C-055).
 *
 * A coupon is read by its own document id — the rules allow a customer to `get`
 * one code while signed in, but never to list them, so nobody can walk the
 * kitchen's discount table. Every rejection comes with the exact reason the
 * customer needs: expired on which date, needs how much more, how many uses were
 * left. "Invalid coupon" is not a sentence this app says.
 */
@Singleton
class CouponRepo @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    data class Coupon(
        val code: String,
        val type: String,
        val value: Long,
        val active: Boolean,
        val minSubtotalAmountPaisa: Long,
        val usageLimit: Long,
        val usedCount: Long,
        val expiresAt: Timestamp?,
        val description: String
    ) {

        fun discountFor(subtotalPaisa: Long): Long =
            Money.applicableDiscount(subtotalPaisa, type, value)

        fun labelFor(subtotalPaisa: Long): String = when (type) {
            "percent" -> "${value.coerceIn(0, 100)} % off"
            else -> "${Money.format(value)} off"
        }
    }

    sealed interface Outcome {
        /** Valid right now for this subtotal, with the paisa it takes off. */
        data class Valid(val coupon: Coupon, val discountPaisa: Long) : Outcome

        data class Rejected(val message: String) : Outcome
    }

    /** Reads and validates one code against the live document and the cart total. */
    suspend fun validate(rawCode: String, subtotalPaisa: Long): Outcome {
        val code = rawCode.trim().uppercase()
        if (code.isBlank()) return Outcome.Rejected("Enter a coupon code.")

        val document = runCatching {
            firestore.collection(COUPONS).document(code).get().await()
        }.getOrElse { error ->
            return Outcome.Rejected("We could not check that code just now: ${error.message ?: "network problem"}.")
        }

        if (!document.exists()) {
            return Outcome.Rejected("That code does not exist. Check the spelling, or ask the kitchen.")
        }

        val map = document.data.orEmpty()
        val coupon = Coupon(
            code = code,
            type = (map["type"] as? String).orEmpty(),
            value = (map["value"] as? Number)?.toLong() ?: 0L,
            active = map["active"] as? Boolean ?: false,
            minSubtotalAmountPaisa = (map["minSubtotalAmountPaisa"] as? Number)?.toLong() ?: 0L,
            usageLimit = (map["usageLimit"] as? Number)?.toLong() ?: 0L,
            usedCount = (map["usedCount"] as? Number)?.toLong() ?: 0L,
            expiresAt = map["expiresAt"] as? Timestamp,
            description = (map["description"] as? String).orEmpty()
        )

        if (!coupon.active) {
            return Outcome.Rejected("${coupon.code} is switched off right now.")
        }

        val expiry = coupon.expiresAt?.toDate()?.time
        if (expiry != null && expiry <= System.currentTimeMillis()) {
            val expiryDate = com.undal.syl.app.core.util.TimeDhaka.formatDate(
                com.undal.syl.app.core.util.TimeDhaka.fromInstant(java.time.Instant.ofEpochMilli(expiry)).toLocalDate()
            )
            return Outcome.Rejected("${coupon.code} expired on $expiryDate.")
        }

        if (coupon.usageLimit in 1..coupon.usedCount) {
            return Outcome.Rejected("${coupon.code} has been used its full ${coupon.usageLimit} times.")
        }

        if (coupon.type != "percent" && coupon.type != "fixed") {
            return Outcome.Rejected("${coupon.code} is not set up correctly — please tell the kitchen.")
        }

        if (subtotalPaisa < coupon.minSubtotalAmountPaisa) {
            val missing = coupon.minSubtotalAmountPaisa - subtotalPaisa
            return Outcome.Rejected(
                "${coupon.code} needs a food subtotal of ${Money.format(coupon.minSubtotalAmountPaisa)}. " +
                    "Add ${Money.format(missing)} more to use it."
            )
        }

        val discount = coupon.discountFor(subtotalPaisa)
        if (discount <= 0L) {
            return Outcome.Rejected("${coupon.code} would not change this order's total.")
        }

        return Outcome.Valid(coupon = coupon, discountPaisa = discount)
    }

    private companion object {
        const val COUPONS = "coupons"
    }
}
