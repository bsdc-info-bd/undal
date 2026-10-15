package com.undal.syl.app.data.repo

import com.google.firebase.firestore.FirebaseFirestore
import com.undal.syl.app.core.net.WorkerApi
import com.undal.syl.app.core.util.CouponMath
import com.undal.syl.app.core.util.Money
import java.util.Locale
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coupons: the code a customer types, and the one the basket applies by itself
 * (C-052 · C-055 · V-071 … V-078).
 *
 * Two sources, one rule. A typed code is read live from `coupons/{CODE}` and
 * judged by [CouponMath] — the Kotlin twin of the Worker's `coupons.ts` and the
 * storefront's `undalEvaluateCoupon`; the checkout re-checks both before the
 * order is written, so nothing here is trusted with money. The *automatic* offer
 * comes from the Worker's public board (`GET /api/store/coupons`), which is the
 * same list the web store prints — one promotion, seen identically by the phone,
 * the browser and the server.
 *
 * Every rejection carries the sentence that tells the truth about *this* basket:
 * expired on which date, needs how much more, how many uses were left. "Invalid
 * coupon" is not a sentence this app says.
 */
@Singleton
class CouponRepo @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val worker: WorkerApi
) {

    /** A coupon as the app reads it, whatever shape Firestore returned. */
    data class Coupon(
        val code: String,
        val type: String,
        val value: Long,
        val active: Boolean,
        val minSubtotalAmountPaisa: Long = 0L,
        val maxDiscountPaisa: Long = 0L,
        val usageLimit: Long = 0L,
        val usedCount: Long = 0L,
        val expiresAtMillis: Long = 0L,
        val startsAt: String = "",
        val endsAt: String = "",
        val daysOfWeek: List<String> = emptyList(),
        val description: String = "",
        val autoApply: Boolean = true,
        val showOnWeb: Boolean = true
    ) {

        /** The shared rule's own shape — the same arithmetic the Worker runs. */
        fun toMath(): CouponMath.Coupon = CouponMath.Coupon(
            code = code,
            type = type,
            value = value,
            active = active,
            description = description,
            minSubtotalAmountPaisa = minSubtotalAmountPaisa,
            maxDiscountPaisa = maxDiscountPaisa,
            expiresAtMillis = expiresAtMillis,
            startsAt = startsAt,
            endsAt = endsAt,
            daysOfWeek = daysOfWeek,
            usageLimit = usageLimit,
            usedCount = usedCount,
            autoApply = autoApply,
            showOnWeb = showOnWeb
        )

        /** What this coupon takes off this basket — food and delivery both. */
        fun discountFor(subtotalPaisa: Long, deliveryPaisa: Long = 0L): Long =
            CouponMath.discountPaisa(subtotalPaisa, deliveryPaisa, type, value, maxDiscountPaisa)

        /** "20% off, up to ৳ 100" · "৳ 50 off" · "Free delivery". */
        fun labelFor(subtotalPaisa: Long = 0L, deliveryPaisa: Long = 0L): String =
            CouponMath.headline(toMath())
    }

    /** An automatic coupon as the cart shows it: what to say, and what it saves. */
    data class Offer(
        val code: String,
        val headline: String,
        val discountPaisa: Long,
        val description: String = ""
    ) {

        /** "SAVE10 applied automatically — ৳ 50 off." */
        val message: String
            get() = "$code applied automatically — ${Money.format(discountPaisa)} off." +
                if (description.isBlank()) "" else " $description"
    }

    sealed interface Outcome {
        /** Valid right now for this subtotal, with the paisa it takes off. */
        data class Valid(val coupon: Coupon, val discountPaisa: Long, val reason: String = "") : Outcome

        data class Rejected(val message: String) : Outcome
    }

    /** The board, cached for a minute so a basket redraw is not a network call. */
    private var cachedBoard: List<Coupon> = emptyList()
    private var cachedAtMillis: Long = 0L

    /**
     * The coupon this basket should apply by itself, or null when none helps.
     *
     * Reads the board the storefront uses, then asks the shared rule which entry
     * saves the most. An empty answer is a real answer: the basket is priced with
     * no coupon rather than with one that silently failed.
     */
    suspend fun automatic(subtotalPaisa: Long, deliveryPaisa: Long): Offer? {
        if (subtotalPaisa <= 0L && deliveryPaisa <= 0L) return null
        val board = board()
        if (board.isEmpty()) return null
        val best = CouponMath.pickBest(
            board.filter { it.autoApply }.map { it.toMath() },
            CouponMath.Context(subtotalPaisa, deliveryPaisa)
        ) ?: return null
        return Offer(
            code = best.code,
            headline = CouponMath.headline(best.type, board.firstOrNull { it.code == best.code }?.value ?: 0L, description = best.description),
            discountPaisa = best.discountPaisa,
            description = best.description
        )
    }

    /** The board itself, for a "today's offers" strip in the cart. */
    suspend fun board(forceRefresh: Boolean = false): List<Coupon> {
        val fresh = System.currentTimeMillis() - cachedAtMillis < BOARD_TTL_MILLIS
        if (!forceRefresh && fresh && cachedBoard.isNotEmpty()) return cachedBoard
        val answer = worker.storeCoupons().getOrNull() ?: return cachedBoard
        val rows = answer.optJSONArray("coupons")
        val board = buildList {
            for (index in 0 until (rows?.length() ?: 0)) {
                val row = rows?.optJSONObject(index) ?: continue
                val code = row.optString("code").trim().uppercase(Locale.ENGLISH)
                if (code.isBlank()) continue
                add(fromBoardRow(code, row))
            }
        }
        if (board.isNotEmpty()) {
            cachedBoard = board
            cachedAtMillis = System.currentTimeMillis()
        }
        return board
    }

    /** Forgets the cache — after the kitchen edits a promotion, or on sign-out. */
    fun invalidate() {
        cachedBoard = emptyList()
        cachedAtMillis = 0L
    }

    /** Reads and validates one code against the live document and the basket. */
    suspend fun validate(rawCode: String, subtotalPaisa: Long, deliveryPaisa: Long = 0L): Outcome {
        val code = rawCode.trim().uppercase(Locale.ENGLISH)
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
            maxDiscountPaisa = (map["maxDiscountPaisa"] as? Number)?.toLong() ?: 0L,
            usageLimit = (map["usageLimit"] as? Number)?.toLong() ?: 0L,
            usedCount = (map["usedCount"] as? Number)?.toLong() ?: 0L,
            expiresAtMillis = CouponMath.expiryMillis(map["expiresAt"] ?: map["expiresAtMillis"]),
            startsAt = (map["startsAt"] as? String).orEmpty(),
            endsAt = (map["endsAt"] as? String).orEmpty(),
            daysOfWeek = CouponMath.days(map["daysOfWeek"]),
            description = (map["description"] as? String).orEmpty(),
            autoApply = map["autoApply"] != false,
            showOnWeb = map["showOnWeb"] != false
        )

        // One rule, three copies: this is the Kotlin one, and the same verdict is
        // reached by the Worker before the order is written.
        val verdict = CouponMath.evaluate(
            coupon.toMath(),
            CouponMath.Context(subtotalPaisa = subtotalPaisa, deliveryPaisa = deliveryPaisa)
        )
        if (!verdict.eligible) {
            return Outcome.Rejected(describe(coupon, verdict.reason, subtotalPaisa))
        }
        return Outcome.Valid(coupon = coupon, discountPaisa = verdict.discountPaisa)
    }

    /**
     * The rule's own word for the refusal, said the way a customer needs to hear
     * it: with the date, the shortfall or the count.
     */
    private fun describe(coupon: Coupon, reason: String, subtotalPaisa: Long): String = when (reason) {
        "switched off" -> "${coupon.code} is switched off right now."
        "not set up correctly" -> "${coupon.code} is not set up correctly — please tell the kitchen."
        "expired" -> {
            val date = com.undal.syl.app.core.util.TimeDhaka.formatDate(
                com.undal.syl.app.core.util.TimeDhaka.fromInstant(
                    java.time.Instant.ofEpochMilli(coupon.expiresAtMillis)
                ).toLocalDate()
            )
            "${coupon.code} expired on $date."
        }
        "not today" -> "${coupon.code} only works on ${coupon.daysOfWeek.joinToString(", ")}."
        "outside its hours" -> "${coupon.code} works between ${coupon.startsAt} and ${coupon.endsAt}."
        "used up" -> "${coupon.code} has been used its full ${coupon.usageLimit} times."
        "basket too small" -> {
            val missing = coupon.minSubtotalAmountPaisa - subtotalPaisa
            "${coupon.code} needs a food subtotal of ${Money.format(coupon.minSubtotalAmountPaisa)}. " +
                "Add ${Money.format(missing)} more to use it."
        }
        else -> "${coupon.code} would not change this order's total."
    }

    /** Maps one row of the Worker's public board into the app's coupon shape. */
    private fun fromBoardRow(code: String, row: JSONObject): Coupon = Coupon(
        code = code,
        type = row.optString("type").trim().lowercase(Locale.ENGLISH),
        value = row.optLong("value", 0L),
        active = true,
        description = row.optString("description"),
        minSubtotalAmountPaisa = row.optLong("minSubtotalAmountPaisa", 0L),
        maxDiscountPaisa = row.optLong("maxDiscountPaisa", 0L),
        expiresAtMillis = row.optLong("expiresAtMillis", 0L),
        startsAt = row.optString("startsAt"),
        endsAt = row.optString("endsAt"),
        daysOfWeek = row.optJSONArray("daysOfWeek")?.let { days ->
            (0 until days.length()).mapNotNull { days.optString(it).takeIf { it.isNotBlank() } }
        }.orEmpty(),
        autoApply = row.optBoolean("autoApply", true),
        showOnWeb = row.optBoolean("showOnWeb", true)
    )

    private companion object {
        const val COUPONS = "coupons"

        /** A minute: long enough that tapping through a basket is free. */
        const val BOARD_TTL_MILLIS = 60_000L
    }
}
