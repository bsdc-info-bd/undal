package com.undal.syl.app.core.util

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

    /** Bangladesh mobile numbers: 11 digits starting 013–019. */
    fun bdMobile(raw: String): String? {
        val value = raw.filter { it.isDigit() }
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

    fun requiredText(label: String, raw: String, minimum: Int = MIN_ADDRESS_TEXT): String? {
        val value = raw.trim()
        return when {
            value.isEmpty() -> "Please add the $label."
            value.length < minimum -> "The $label needs at least $minimum characters."
            else -> null
        }
    }

    fun addressTitle(raw: String): String? = requiredText("address title", raw)

    fun addressDescription(raw: String): String? = requiredText("address description", raw)

    fun deliveryNote(raw: String): String? = requiredText("delivery note", raw)

    fun couponCode(raw: String): String? {
        val value = raw.trim().uppercase()
        return when {
            value.isEmpty() -> "Enter the coupon code."
            !COUPON_CODE.matches(value) -> "Coupon codes use letters, numbers, - and _ only."
            else -> null
        }
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
