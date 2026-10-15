package com.undal.syl.app.core.util

/**
 * Digits in the scripts people actually type with.
 *
 * A phone sold in Sylhet usually offers a Bangla keyboard, and the Bangla
 * keyboard's number row is **০১২৩৪৫৬৭৮৯**, not `0123456789`. Kotlin's
 * `Char.isDigit()` returns true for those digits, so every filter in the app that
 * said "keep the digits" kept them — and then every parser said no:
 *
 *  · a phone number typed as ০১৭১২৩৪৫৬৭৮ was rejected as "not 11 digits",
 *  · a price typed as ১৮০ was rejected as "not a number",
 *  · a usage limit of ৫০ silently became 0, which in that field means *unlimited*,
 *  · and `tel:০১৭…`, handed to the dialer, opens nothing.
 *
 * That is not an edge case in Bangladesh; it is what happens when somebody who
 * reads Bangla uses the app. `Character.digit(ch, 10)` answers the Unicode digit
 * value for any script — Bangla, Devanagari, Arabic-Indic, fullwidth — so one
 * conversion at the edges makes every parser in the app work in every script,
 * and every number the app *writes* stays ASCII, which is what Firebase, the
 * security rules, the Worker and a GPS receiver all expect.
 *
 * Nothing here is locale-dependent on purpose: `Locale` decides how a number is
 * written for people, never what the number is.
 */
object UndalDigits {

    /** Every digit, in every script, as the ASCII digit the rest of the app reads. */
    fun toAscii(raw: String): String {
        var needsConversion = false
        for (ch in raw) {
            if (ch > '\u0080') {
                needsConversion = true
                break
            }
        }
        if (!needsConversion) return raw
        val builder = StringBuilder(raw.length)
        for (ch in raw) {
            val value = if (ch in '0'..'9') ch - '0' else Character.digit(ch, 10)
            if (value in 0..9) builder.append('0' + value) else builder.append(ch)
        }
        return builder.toString()
    }

    /** Just the digits, in ASCII, capped — for counters like position or usage limit. */
    fun digitsOnly(raw: String, maxDigits: Int = 6): String =
        toAscii(raw).filter { it in '0'..'9' }.take(maxDigits)

    /**
     * A decimal number as typed: Bengali digits converted, thousands separators
     * and the taka sign dropped, a comma treated as the decimal point (which is
     * what the bn_BD and most European keyboards produce), and at most one dot.
     */
    fun cleanDecimal(raw: String, maxDecimals: Int = 2, maxWholeDigits: Int = 7): String {
        val ascii = toAscii(raw)
        val builder = StringBuilder(ascii.length)
        var wholeDigits = 0
        var decimals = 0
        var seenDot = false
        for (ch in ascii) {
            when {
                ch in '0'..'9' -> {
                    if (!seenDot) {
                        if (wholeDigits >= maxWholeDigits) continue
                        wholeDigits++
                    } else {
                        if (decimals >= maxDecimals) continue
                        decimals++
                    }
                    builder.append(ch)
                }

                ch == '.' || ch == ',' -> if (!seenDot) {
                    seenDot = true
                    builder.append('.')
                }
            }
        }
        return builder.toString()
    }

    /**
     * A clock time as typed, turned into `HH:MM` as far as it can be.
     *
     * "9" → "9", "930" → "9:30", "09.30" → "09:30", "১৮:০০" → "18:00". The field
     * is left alone while it is still ambiguous, and `Validators.clockTime` is the
     * thing that decides whether it is a real time — the field's job is only to
     * make the colon reachable on a keypad that has no colon key.
     */
    fun cleanClock(raw: String): String {
        val digits = toAscii(raw).filter { it in '0'..'9' }
        if (digits.isEmpty()) return ""
        return when {
            digits.length <= 2 -> digits
            digits.length == 3 -> "${digits[0]}:${digits.substring(1)}"
            else -> "${digits.substring(0, 2)}:${digits.substring(2, 4)}"
        }
    }

    /** Bengali-digit aware `toLongOrNull`, for anything the kitchen types. */
    fun toLongOrNull(raw: String): Long? = toAscii(raw).trim().toLongOrNull()

    /** Bengali-digit aware `toIntOrNull`. */
    fun toIntOrNull(raw: String): Int? = toAscii(raw).trim().toIntOrNull()

    /**
     * A number written the way a technician reads it: ASCII digits, an ASCII dot,
     * whatever the phone's language happens to be.
     *
     * `"%.1f".format(4.5)` in a Bangla locale writes **৪.৫**, and in a German one
     * 4,5 — while every other number on the same screen (prices, quantities, the
     * order id) is ASCII. One screen with two digit systems is not a translation,
     * it is a bug; and a coordinate reading that cannot be typed into another app
     * is worse than no coordinate at all.
     */
    fun format(value: Double, decimals: Int): String =
        String.format(java.util.Locale.ENGLISH, "%.${decimals}f", value)

    /** A whole number with the same guarantee. */
    fun formatWhole(value: Double): String = format(value, 0)
}
