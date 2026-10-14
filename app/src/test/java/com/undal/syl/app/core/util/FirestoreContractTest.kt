package com.undal.syl.app.core.util

import com.undal.syl.app.core.maps.UndalLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security rules and the app must agree about what a valid write is.
 *
 * When they disagree the database answers with nothing but "Missing or
 * insufficient permissions", which looks exactly like a broken kitchen and is
 * the most expensive kind of bug in this product. These tests pin the parts of
 * `firebase/firestore.rules` the app itself produces, so a drift between the two
 * fails here — in two seconds, on a laptop — instead of on a customer's phone.
 *
 * Each assertion names the rule it mirrors.
 */
class FirestoreContractTest {

    /** `match /users/{userId}` create: `phone.matches('^01[3-9][0-9]{8}$')`. */
    private val rulePhone = Regex("^01[3-9][0-9]{8}$")

    /** `match /users/{userId}` create: `email.matches('^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$')`. */
    private val ruleEmail = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

    /** `match /users/{userId}` create: `address.note.size() <= 300`. */
    private val ruleAddressTextLimit = 300

    @Test
    fun everyNumberAProfileCanCarryIsAcceptedByTheRules() {
        // The normaliser is what the profile write goes through, so every shape
        // it accepts has to satisfy the rule the database applies.
        val typedByCustomers = listOf(
            "01712345678",
            "01712 345678",
            "+880 1712-345678",
            "8801712345678",
            "880 1712345678"
        )
        typedByCustomers.forEach { typed ->
            val normalized = Validators.normalizeBdMobile(typed)
            assertNotNull("$typed should normalise", normalized)
            assertTrue(
                "$typed became $normalized, which the security rules would refuse",
                rulePhone.matches(normalized!!)
            )
        }
    }

    @Test
    fun theEmailWrittenToTheProfileSatisfiesTheRule() {
        // `AuthRepo.saveCustomerProfile` checks the account's email with this
        // validator before it writes, so the validator and the rule must agree.
        val accepted = listOf("kitchen@undal.local", "orders+undal@gmail.com", "a@b.co")
        accepted.forEach { email ->
            assertNull("the validator rejects $email", Validators.email(email))
            assertTrue("the rules would refuse $email", ruleEmail.matches(email))
        }
    }

    @Test
    fun localPartOfTheEmailTheRuleRefusesIsAlsoRefusedHere() {
        val refused = listOf("kitchen@undal", "kitchen.undal.local", "", "a b@undal.local")
        refused.forEach { email ->
            assertNotNull("the validator accepts $email", Validators.email(email))
        }
    }

    @Test
    fun theAddressCarriesEveryKeyFullAddressDemands() {
        val location = UndalLocation(
            lat = 24.8949,
            lng = 91.8687,
            title = "Home",
            desc = "Flat 4B, Bandar Bazar",
            note = "Call when you reach the gate",
            geohashLabel = "Zindabazar, Sylhet"
        )
        val written = location.toFirestoreMap()
        // fullAddress() in the rules: hasAll(['lat', 'lng', 'title', 'desc', 'note']).
        listOf("lat", "lng", "title", "desc", "note").forEach { key ->
            assertTrue("the address map has no $key", written.containsKey(key))
        }
        assertTrue(written["lat"] is Double)
        assertTrue(written["lng"] is Double)
        assertTrue(location.isValid)
    }

    @Test
    fun theAddressTextIsCappedExactlyWhereTheRulesCapIt() {
        // A longer description or note is refused by the database, so the
        // validator has to stop it first — with a sentence about the field.
        assertNull(Validators.addressDescription("x".repeat(ruleAddressTextLimit)))
        assertNotNull(Validators.addressDescription("x".repeat(ruleAddressTextLimit + 1)))
        assertNull(Validators.deliveryNote("x".repeat(ruleAddressTextLimit)))
        assertNotNull(Validators.deliveryNote("x".repeat(ruleAddressTextLimit + 1)))
        // The minimum the rules demand (nonBlankString = 3 characters) is the
        // minimum the form demands.
        assertNull(Validators.addressDescription("abc"))
        assertNotNull(Validators.addressDescription("ab"))
    }

    @Test
    fun theStoreNameAndDishNamesStayInsideTheRuleLimits() {
        // `match /users` caps the name at 80; the same constant is used for the
        // dish and store names so one rule never surprises a second screen.
        assertNull(Validators.name("x".repeat(80)))
        assertNotNull(Validators.name("x".repeat(81)))
    }

    @Test
    fun moneyTotalsAreTheNumbersTheOrderRuleRecomputes() {
        // `match /orders` create: total == subtotal + delivery - discount, > 0.
        val subtotal = 58_500L
        val delivery = 4_000L
        val discount = 5_000L
        val total = Money.grandTotal(subtotal, delivery, discount)
        assertEquals(subtotal + delivery - discount, total)
        assertTrue(total > 0)
        // And a discount can never flip a total negative — the rule checks the
        // same subtraction, so both sides must clamp identically.
        assertEquals(0L, Money.grandTotal(10_000, 0, 20_000))
    }
}
