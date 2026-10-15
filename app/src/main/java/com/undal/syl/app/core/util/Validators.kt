package com.undal.syl.app.core.util
import java.util.Locale

/**
 * Field validation for every form in the app.
 *
 * Messages are specific and actionable — the customer is told exactly what to
 * change, and the same rules are mirrored in the Firestore rules so the client
 * can never disagree with the server about what is acceptable.
 */
object Validators {

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")
    private val BD_MOBILE = Regex("^01[3-9]\\d{8}$")
    private val COUPON_CODE = Regex("^[A-Z0-9_-]{3,24}$")
    private const val MIN_PASSWORD_LENGTH = 6
    private const val MAX_NAME_LENGTH = 80
    private const val MIN_ADDRESS_TEXT = 3
    /** The rules' `nonBlankString()` floor, shared by every name in the menu. */
    const val MIN_MENU_NAME = 3

    /** The rules cap `address.desc` and `address.note` at 300 characters each. */
    private const val MAX_ADDRESS_TEXT = 300

    /** A title is a label the rider reads at a glance, not a paragraph. */
    private const val MAX_ADDRESS_TITLE = 160

    fun name(raw: String): String? {
        val value = raw.trim()
        return when {
            value.isEmpty() -> "Please enter your full name."
            value.length < 3 -> "That looks too short — use your full name."
            value.length > MAX_NAME_LENGTH -> "Please keep the name under $MAX_NAME_LENGTH characters."
            value.all { !it.isLetter() && it != ' ' && it != '.' && it != '-' } -> "Please use letters in your name."
            else -> null
        }
    }

    fun email(raw: String): String? {
        val value = raw.trim()
        return when {
            value.isEmpty() -> "Please enter your email address."
            !EMAIL.matches(value) -> "That email address is missing something (name@example.com)."
            else -> null
        }
    }

    /**
     * Turns anything a customer typed — `+880 1712-345678`, `8801712345678`,
     * `01712 345678` — into the `01XXXXXXXXX` shape the security rules require.
     * Returns null when it cannot be a Bangladeshi mobile number.
     */
    fun normalizeBdMobile(raw: String): String? {
        val digits = UndalDigits.toAscii(raw).filter { it in '0'..'9' }
        val local = when {
            digits.length == 14 && digits.startsWith("880") -> digits.substring(3)
            digits.length == 13 && digits.startsWith("880") -> digits.substring(2)
            digits.length == 12 && digits.startsWith("88") -> digits.substring(2)
            digits.length == 11 -> digits
            else -> return null
        }
        return local.takeIf { BD_MOBILE.matches(it) }
    }

    /**
     * Bangladesh mobile numbers: 11 digits starting 013–019.
     *
     * ০১৭১২৩৪৫৬৭৮ is the same number as 01712345678 — the Bangla number row is
     * what a Bangla keyboard offers, and telling a customer their own number is
     * "not 11 digits" because they typed it in the script they read is a bug, not
     * validation.
     */
    fun bdMobile(raw: String): String? {
        val value = UndalDigits.toAscii(raw).filter { it in '0'..'9' }
        return when {
            value.isEmpty() -> "Please enter your mobile number."
            !BD_MOBILE.matches(value) -> "Use the 11-digit number, like 01712345678."
            else -> null
        }
    }

    fun password(raw: String): String? = when {
        raw.isEmpty() -> "Please choose a password."
        raw.length < MIN_PASSWORD_LENGTH -> "Use at least $MIN_PASSWORD_LENGTH characters."
        raw.all { it.isDigit() } -> "Add a letter as well as numbers."
        else -> null
    }

    fun passwordConfirmation(password: String, confirmation: String): String? = when {
        confirmation.isEmpty() -> "Please type the password again."
        confirmation != password -> "The two passwords do not match."
        else -> null
    }

    /**
     * Length the way the database counts it.
     *
     * Firestore's `string.size()` — what `nonBlankString()` in the rules compares
     * against — counts Unicode code points, while Kotlin's `String.length` counts
     * UTF-16 units. They disagree on anything outside the BMP, so an editor that
     * measured `length` could accept a value the rules then refused, and a rule
     * refusal reaches the screen as "Missing or insufficient permissions" instead
     * of "that name is too short". Counting the same thing the rules count is what
     * keeps the sentence in the editor true.
     */
    private fun textLength(value: String): Int = value.codePointCount(0, value.length)

    fun requiredText(label: String, raw: String, minimum: Int = MIN_ADDRESS_TEXT, maximum: Int = 0): String? {
        val value = raw.trim()
        val length = textLength(value)
        return when {
            value.isEmpty() -> "Please add the $label."
            length < minimum -> "The $label needs at least $minimum characters."
            maximum > 0 && length > maximum -> "Please keep the $label under $maximum characters."
            else -> null
        }
    }

    /**
     * Any name the kitchen writes into the menu: a category, a subcategory, a
     * dish, a combo, a campaign title.
     *
     * All five are guarded in the rules by the same `nonBlankString()`, which is
     * `size() >= 3` — so three characters is not a taste choice made here, it is
     * the database's own floor, and an editor that accepts two produces a save
     * that is refused with no explanation. Two characters is not rare in Bangla
     * either: "চা" is tea.
     */
    fun menuName(label: String, raw: String): String? = requiredText(label, raw, minimum = MIN_MENU_NAME)

    /**
     * The address fields the security rules store.
     *
     * The description and the delivery note are capped at the same 300
     * characters the rules enforce, so a long note is answered with a sentence
     * about the note instead of a permission refusal from the database. The
     * title is a label, so it is held much shorter.
     */
    fun addressTitle(raw: String): String? = requiredText("address title", raw, maximum = MAX_ADDRESS_TITLE)

    fun addressDescription(raw: String): String? =
        requiredText("address description", raw, maximum = MAX_ADDRESS_TEXT)

    fun deliveryNote(raw: String): String? = requiredText("delivery note", raw, maximum = MAX_ADDRESS_TEXT)

    fun couponCode(raw: String): String? {
        val value = raw.trim().uppercase(Locale.ENGLISH)
        return when {
            value.isEmpty() -> "Enter the coupon code."
            !COUPON_CODE.matches(value) -> "Coupon codes use letters, numbers, - and _ only."
            else -> null
        }
    }

    private val CLOCK = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    /**
     * A time the kitchen typed: `09:00`, `9:00`, `9.30`, `0930`, `৯:০০`.
     *
     * Returns the canonical `HH:MM` — what the schedule stores, what the
     * storefront prints and what the Worker's opening cron compares against. The
     * opening hours are the one field where a five-minute mistake is the
     * difference between "Open" and a closed shop on the web, so the value is
     * normalised once here instead of being patched in three places later.
     */
    fun clockTime(raw: String): String? {
        val cleaned = UndalDigits.cleanClock(raw)
        if (cleaned.isEmpty()) return null
        if (!CLOCK.matches(cleaned)) return null
        val (hours, minutes) = cleaned.split(":")
        return hours.padStart(2, '0') + ":" + minutes
    }

    /** The same rule as a field error, for a schedule row. */
    fun clockTimeError(label: String, raw: String): String? = when {
        raw.isBlank() -> "Please add the $label time."
        clockTime(raw) == null -> "Use 24-hour time, like 09:00 or 21:30."
        else -> null
    }

    fun rating(value: Int): String? =
        if (value in 1..3) null else "Choose how spicy the dish should be."

    /** Four-step assessment with a plain-English label (used by the strength meter). */
    fun assessPassword(password: String): PasswordAssessment {
        var score = 0
        if (password.length >= MIN_PASSWORD_LENGTH) score += 1
        if (password.length >= 10) score += 1
        if (password.any { it.isDigit() } && password.any { it.isLetter() }) score += 1
        if (password.any { !it.isLetterOrDigit() }) score += 1
        return when {
            password.isEmpty() -> PasswordAssessment(0, "Use at least 6 characters")
            score <= 1 -> PasswordAssessment(1, "Weak — add more characters")
            score == 2 -> PasswordAssessment(2, "Okay — add a number or a symbol")
            score == 3 -> PasswordAssessment(3, "Strong")
            else -> PasswordAssessment(4, "Very strong")
        }
    }
}

data class PasswordAssessment(val score: Int, val label: String)
