package com.undal.syl.app.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Calling people without asking for a scary permission.
 *
 * Undal opens the dialer with the number already typed (ACTION_DIAL) instead of
 * placing the call itself, so the app never requests CALL_PHONE, the customer
 * sees exactly who is being called before it happens, and a mistyped number can
 * be corrected in the dialer instead of being dialled straight away.
 */
object UndalPhone {

    /**
     * The number as digits the dialer can use, or null when there is nothing
     * sensible to dial.
     *
     * Digits arrive in any script (the Bangla number row is ০১২…), with spaces,
     * with a `+`, with a `-`, or with an 880 country code — all of which mean the
     * *same* number. `tel:০১৭…` is not a phone number as far as the dialer is
     * concerned, so the conversion happens here rather than being hoped for.
     *
     * Six digits is the floor: short codes and landlines are real in Sylhet, and
     * the dialer is opened for a human to confirm, never dialled silently.
     */
    fun dialableDigits(number: String?): String? {
        val digits = com.undal.syl.app.core.util.UndalDigits.toAscii(number.orEmpty())
            .filter { it in '0'..'9' }
        if (digits.length < 6 || digits.length > 15) return null
        // A number that is one digit repeated is a placeholder, not a phone.
        if (digits.all { it == digits[0] }) return null
        return digits
    }

    /** True when the number looks like something the dialer can dial. */
    fun isDialable(number: String?): Boolean = dialableDigits(number) != null

    fun dial(context: Context, number: String?) {
        val digits = dialableDigits(number) ?: return
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Opens WhatsApp with the number, which is how Sylhet actually confirms details. */
    fun whatsApp(context: Context, number: String?, text: String = "") {
        val digits = dialableDigits(number) ?: return
        val international = if (digits.startsWith("0")) "880" + digits.substring(1) else digits
        val url = buildString {
            append("https://wa.me/").append(international)
            if (text.isNotBlank()) append("?text=").append(Uri.encode(text))
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Formats 01712345678 as 01712 345678, which is how people read it out.
     *
     * `pretty("০১৭১২৩৪৫৬৭৮")` prints 01712 345678: a number is a number, and the
     * digits it is printed in are the app's, not the keyboard's.
     */
    fun pretty(number: String?): String {
        val digits = com.undal.syl.app.core.util.UndalDigits.toAscii(number.orEmpty())
            .filter { it in '0'..'9' }
        if (digits.length != 11) return digits
        return "${digits.substring(0, 5)} ${digits.substring(5)}"
    }
}
