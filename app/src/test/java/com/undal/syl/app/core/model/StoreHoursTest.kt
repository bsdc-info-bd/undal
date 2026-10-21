package com.undal.syl.app.core.model

import com.undal.syl.app.core.util.TimeDhaka
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * The store keeps its schedule.
 *
 * The reported failure: a kitchen that published its hours took orders around
 * the clock, because the switch alone decided — the customer screen even said
 * "Open outside the published hours — the kitchen is cooking right now anyway".
 * The rule now is "switch on ∧ not paused ∧ the hours cover now".
 *
 * The fixture week and every vector here are the ones in
 * `worker/test/store-hours.test.ts`, so the phone, the web page and the web
 * checkout cannot disagree about a single minute.
 */
class StoreHoursTest {

    /** A minute in Dhaka. */
    private fun at(local: String): ZonedDateTime =
        LocalDateTime.parse(local.replace(" ", "T")).atZone(TimeDhaka.zone)

    private val split = listOf(StoreSlot("11:00", "15:00"), StoreSlot("19:00", "23:00"))

    // 2026-10-19 is a Monday. The fixture week runs Mon 19 … Mon 26 October.
    private val kitchen = StoreConfig.unloaded().copy(
        open = true,
        acceptingOrders = true,
        openSynced = true,
        autoClosed = false,
        storeLat = 24.8949,
        storeLng = 91.8687,
        radiusKm = 5.0,
        weekly = mapOf(
            "mon" to split,
            "tue" to split,
            "wed" to split,
            "thu" to split,
            // A late kitchen: the Friday service runs past midnight.
            "fri" to listOf(StoreSlot("20:00", "01:00")),
            "sat" to emptyList(),
            // The 24/7 template writes 23:59, which must not close the store for a minute.
            "sun" to listOf(StoreSlot("00:00", "23:59"))
        ),
        specialDates = listOf(
            StoreSpecialDate("2026-10-21", closed = true, slots = emptyList(), note = "Family wedding"),
            StoreSpecialDate("2026-10-22", closed = false, slots = listOf(StoreSlot("09:00", "12:00"))),
            // Marked open with no hours: the builder tells the owner it "behaves like a normal day".
            StoreSpecialDate("2026-10-23", closed = false, slots = emptyList())
        )
    )

    private val openAt = listOf(
        Triple("2026-10-19 10:59", false, "a minute before the lunch service"),
        Triple("2026-10-19 11:00", true, "the opening minute is open"),
        Triple("2026-10-19 14:59", true, "the last minute of lunch"),
        Triple("2026-10-19 15:00", false, "the closing minute is closed"),
        Triple("2026-10-19 18:30", false, "between the services"),
        Triple("2026-10-19 22:59", true, "late dinner"),
        Triple("2026-10-19 23:00", false, "after dinner"),
        Triple("2026-10-20 00:30", false, "Monday's slots do not cross midnight"),
        Triple("2026-10-21 12:00", false, "a day off overrides the weekly lunch"),
        Triple("2026-10-22 10:00", true, "special hours apply"),
        Triple("2026-10-22 13:00", false, "special hours replace the weekly lunch"),
        Triple("2026-10-22 20:00", false, "special hours replace the weekly dinner too"),
        Triple("2026-10-23 21:00", true, "a special date with no hours behaves like a normal Friday"),
        Triple("2026-10-24 00:30", true, "Friday's late service runs into Saturday, a day off or not"),
        Triple("2026-10-24 01:00", false, "and ends at its closing minute"),
        Triple("2026-10-24 12:00", false, "Saturday has no hours"),
        Triple("2026-10-25 00:00", true, "24/7 from the first minute"),
        Triple("2026-10-25 23:59", true, "to the last: 23:59 means midnight"),
        Triple("2026-10-26 00:00", false, "Monday starts at eleven again"),
        Triple("2026-10-16 00:30", false, "a Friday after midnight belongs to Thursday, which ended at 23:00"),
        Triple("2026-10-16 20:30", true, "the Friday service"),
        Triple("2026-10-17 00:30", true, "the Friday service, after midnight")
    )

    @Test
    fun theHoursDecideTheMinuteIncludingServicesThatRunPastMidnight() {
        openAt.forEach { (local, expected, why) ->
            assertEquals("$local: $why", expected, StoreHours.isOpenAt(kitchen, at(local)))
        }
    }

    @Test
    fun aSwitchedOnStoreIsTakingOrdersExactlyWhenItsHoursSaySo() {
        openAt.forEach { (local, expected, why) ->
            assertEquals("$local: $why", expected, StoreHours.isTakingOrders(kitchen, at(local)))
            assertEquals("$local: $why", expected, StoreOpenState.of(kitchen, at(local)).serviceable)
        }
        // The reported bug, as one line: the switch is on and it is 3 a.m. on a Tuesday.
        val threeAm = StoreOpenState.of(kitchen, at("2026-10-20 03:00"))
        assertFalse(threeAm.serviceable)
        assertEquals("Closed now", threeAm.headline)
        assertEquals(StoreOpenState.Reason.OUTSIDE_HOURS, threeAm.reason)
        assertTrue(threeAm.opensByItself)
        assertFalse(threeAm.detail.contains("cooking right now anyway"))
    }

    @Test
    fun theNextOpeningIsNamedTheWayACustomerReadsIt() {
        assertEquals("today at 7:00 PM", StoreHours.nextOpening(kitchen, at("2026-10-19 16:00"))?.label)
        assertEquals("tomorrow at 11:00 AM", StoreHours.nextOpening(kitchen, at("2026-10-19 23:30"))?.label)
        // Wednesday is a day off, so Tuesday night's next opening is Thursday's special hours.
        assertEquals("on Thursday at 9:00 AM", StoreHours.nextOpening(kitchen, at("2026-10-20 23:30"))?.label)
        assertEquals("tomorrow at 12:00 AM", StoreHours.nextOpening(kitchen, at("2026-10-24 02:00"))?.label)
        assertNull(StoreHours.nextOpening(kitchen.copy(weekly = emptyMap(), specialDates = emptyList()), at("2026-10-19 12:00")))
    }

    @Test
    fun anOpenStoreSaysWhenItCloses() {
        assertEquals("3:00 PM", StoreHours.closingLabel(kitchen, at("2026-10-19 12:00")))
        assertEquals("1:00 AM", StoreHours.closingLabel(kitchen, at("2026-10-23 21:00")))
        assertEquals("1:00 AM", StoreHours.closingLabel(kitchen, at("2026-10-24 00:30")))
        assertEquals("midnight", StoreHours.closingLabel(kitchen, at("2026-10-25 12:00")))
        assertNull(StoreHours.closingLabel(kitchen, at("2026-10-19 16:00")))
        assertEquals("Open until 3:00 PM", StoreOpenState.of(kitchen, at("2026-10-19 12:00")).nextOpeningLine)
        assertEquals("Opens today at 7:00 PM", StoreOpenState.of(kitchen, at("2026-10-19 16:00")).nextOpeningLine)
    }

    @Test
    fun aTimeIsReadTheWayAnOwnerWritesItAndGarbageIsNotMidnight() {
        assertEquals(540, StoreHours.clockMinutes("09:00"))
        assertEquals(540, StoreHours.clockMinutes("9:00"))
        assertEquals(1290, StoreHours.clockMinutes(" 21:30 "))
        assertEquals("Bengali digits, as a Bangla keyboard types them", 570, StoreHours.clockMinutes("০৯:৩০"))
        assertEquals(1440, StoreHours.clockMinutes("24:00"))
        listOf("25:00", "9", "09:60", "", "nine", "9.30").forEach { bad ->
            assertNull(bad, StoreHours.clockMinutes(bad))
        }
        assertNull(StoreHours.clockMinutes(null))
        assertNull(StoreHours.window(StoreSlot("late", "22:00")))
        assertNull("an empty slot", StoreHours.window(StoreSlot("09:00", "09:00")))
        assertEquals(StoreHours.Window(1080, 1440), StoreHours.window(StoreSlot("18:00", "00:00")))
        assertEquals(StoreHours.Window(1200, 60), StoreHours.window(StoreSlot("20:00", "01:00")))
    }

    @Test
    fun aDayOffIsADayOffInBothSpellingsAndWeekdayKeysMayBeWrittenOut() {
        val older = StoreSpecialDate.fromMap(mapOf("date" to "2026-10-19", "mode" to "closed"))
        assertTrue(older?.closed == true)
        assertFalse(StoreHours.isOpenAt(kitchen.copy(specialDates = listOfNotNull(older)), at("2026-10-19 12:00")))
        val spelledOut = kitchen.copy(weekly = mapOf("Monday" to listOf(StoreSlot("11:00", "15:00"))), specialDates = emptyList())
        assertTrue(StoreHours.isOpenAt(spelledOut, at("2026-10-19 12:00")))
    }

    @Test
    fun theSwitchIsTheOwnersLatestWordWhicheverBuildWroteIt() {
        val bare = StoreConfig.unloaded()
        // Documents no schedule-aware writer has touched: `open` is the switch.
        assertTrue(bare.copy(open = true).switchOn)
        assertFalse(bare.copy(open = false).switchOn)
        // Written by a schedule-aware build: the switch is recorded on its own.
        assertTrue(bare.copy(acceptingOrders = true, open = false, openSynced = false).switchOn)
        assertFalse(bare.copy(acceptingOrders = false, open = false, openSynced = false).switchOn)
        assertTrue(bare.copy(acceptingOrders = true).switchOn)
        // An older vendor build pressed Close (or Open) after that: that press wins.
        assertFalse(bare.copy(acceptingOrders = true, open = false, openSynced = true).switchOn)
        assertTrue(bare.copy(acceptingOrders = false, open = true, openSynced = false).switchOn)

        val fromDocument = StoreConfig.fromMap(mapOf("open" to false, "acceptingOrders" to true, "openSynced" to false))
        assertTrue(fromDocument.switchOn)
    }

    @Test
    fun pausedAutoClosedAndOutsideHoursAreThreeDifferentClosedStores() {
        val lunch = at("2026-10-19 12:00")
        val paused = kitchen.copy(acceptingOrders = false, open = false, openSynced = false, manualOverrideNote = "Back on Friday.")
        assertEquals(StoreOpenState.Reason.PAUSED, StoreOpenState.of(paused, lunch).reason)
        assertEquals("Back on Friday.", StoreOpenState.of(paused, lunch).detail)
        assertFalse(StoreOpenState.of(paused, lunch).switchOn)

        val offline = kitchen.copy(autoClosed = true, open = false, openSynced = false)
        assertEquals(StoreOpenState.Reason.AUTO_CLOSED, StoreOpenState.of(offline, lunch).reason)

        val evening = StoreOpenState.of(kitchen, at("2026-10-19 16:00"))
        assertEquals(StoreOpenState.Reason.OUTSIDE_HOURS, evening.reason)
        assertEquals("We open today at 7:00 PM. Your cart is kept until then.", evening.detail)
        assertTrue(evening.switchOn)

        val wedding = StoreOpenState.of(kitchen, at("2026-10-21 12:00"))
        assertEquals("Closed today — Family wedding. We open tomorrow at 9:00 AM. Your cart is kept until then.", wedding.detail)
        assertEquals("Closed today · Family wedding", wedding.todayLine)

        val noHours = StoreOpenState.of(kitchen.copy(weekly = emptyMap(), specialDates = emptyList()), lunch)
        assertEquals(StoreOpenState.Reason.NO_HOURS, noHours.reason)

        val unpinned = StoreOpenState.of(kitchen.copy(storeLat = 0.0, storeLng = 0.0), lunch)
        assertEquals(StoreOpenState.Reason.SETTING_UP, unpinned.reason)

        val open = StoreOpenState.of(kitchen, lunch)
        assertTrue(open.serviceable)
        assertEquals("Open now", open.headline)
        assertEquals("Cooking today: 11:00 AM – 3:00 PM · 7:00 PM – 11:00 PM", open.detail)
    }
}
