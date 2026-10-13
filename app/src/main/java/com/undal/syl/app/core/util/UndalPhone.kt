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

    /** True when the number looks like a Bangladeshi mobile number. */
    fun isDialable(number: String?): Boolean {
        val digits = number?.filter { it.isDigit() }.orEmpty()
        return digits.length >= 6
    }

    fun dial(context: Context, number: String?) {
        if (!isDialable(number)) return
        val digits = number!!.filter { it.isDigit() }
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Opens WhatsApp with the number, which is how Sylhet actually confirms details. */
    fun whatsApp(context: Context, number: String?, text: String = "") {
        if (!isDialable(number)) return
        val digits = number!!.filter { it.isDigit() }
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

    /** Formats 01712345678 as 01712 345678, which is how people read it out. */
    fun pretty(number: String?): String {
        val digits = number?.filter { it.isDigit() }.orEmpty()
        if (digits.length != 11) return digits
        return "${digits.substring(0, 5)} ${digits.substring(5)}"
    }
}
