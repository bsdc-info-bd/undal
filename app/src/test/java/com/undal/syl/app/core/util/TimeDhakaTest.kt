package com.undal.syl.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Dhaka time decisions must be stable: the same logic runs on the phone, in the
 * Worker cron and in the store schedule builder.
 */
class TimeDhakaTest {

    @Test
    fun zonaIsFixedAtPlusSix() {
        assertEquals(6 * 60 * 60, TimeDhaka.zone.rules.getOffset(Instant.now()).totalSeconds)
    }

    @Test
    fun utcNoonIsSixInTheEveningInDhaka() {
        val instant = Instant.parse("2026-10-13T12:00:00Z")
        val dhaka = TimeDhaka.fromInstant(instant)
        assertEquals(18, dhaka.hour)
        assertEquals(0, dhaka.minute)
        assertEquals("6:00 PM", TimeDhaka.formatInstant(instant))
    }

    @Test
    fun weekdayKeysMatchTheStoreDocument() {
        // 2026-10-13 is a Tuesday.
        assertEquals("tue", TimeDhaka.weekdayKey(LocalDate.of(2026, 10, 13)))
        assertEquals("mon", TimeDhaka.weekdayKey(LocalDate.of(2026, 10, 12)))
        assertEquals("sun", TimeDhaka.weekdayKey(LocalDate.of(2026, 10, 11)))
        assertEquals("sat", TimeDhaka.weekdayKey(LocalDate.of(2026, 10, 17)))
    }

    @Test
    fun dateKeyMatchesTheSpecialDateFormat() {
        assertEquals("2026-10-13", TimeDhaka.dateKey(LocalDate.of(2026, 10, 13)))
    }

    @Test
    fun slotChecksHandleNormalAndMidnightCrossingWindows() {
        val nine = TimeDhaka.minutesFromHhMm("09:00")!!
        val ten = TimeDhaka.minutesFromHhMm("22:00")!!
        assertTrue(TimeDhaka.isWithinSlot(nine, ten, TimeDhaka.minutesFromHhMm("13:30")!!))
        assertFalse(TimeDhaka.isWithinSlot(nine, ten, TimeDhaka.minutesFromHhMm("22:00")!!))

        val evening = TimeDhaka.minutesFromHhMm("20:00")!!
        val early = TimeDhaka.minutesFromHhMm("01:00")!!
        assertTrue(TimeDhaka.isWithinSlot(evening, early, TimeDhaka.minutesFromHhMm("23:30")!!))
        assertTrue(TimeDhaka.isWithinSlot(evening, early, TimeDhaka.minutesFromHhMm("00:30")!!))
        assertFalse(TimeDhaka.isWithinSlot(evening, early, TimeDhaka.minutesFromHhMm("12:00")!!))
        assertFalse("empty slot", TimeDhaka.isWithinSlot(nine, nine, TimeDhaka.minutesFromHhMm("13:00")!!))
    }

    @Test
    fun countdownFormatsMinutesAndSeconds() {
        val now = Instant.parse("2026-10-13T12:00:00Z")
        assertEquals("07:32", TimeDhaka.countdown(now.plusSeconds(452), now))
        assertEquals("00:09", TimeDhaka.countdown(now.plusSeconds(9), now))
        assertEquals("OVERDUE 01:10", TimeDhaka.countdown(now.minusSeconds(70), now))
        assertEquals("—", TimeDhaka.countdown(null, now))
    }

    @Test
    fun overdueDetectionMatchesTheKitchenEscalation() {
        val now = Instant.parse("2026-10-13T12:00:00Z")
        assertFalse(TimeDhaka.isPrepOverdue(now.plusSeconds(60), now))
        assertTrue(TimeDhaka.isPrepOverdue(now.minusSeconds(1), now))
        assertFalse(TimeDhaka.isPrepOverdue(null, now))
    }

    @Test
    fun relativeLabelsReadNaturally() {
        val now = Instant.now()
        assertEquals("just now", TimeDhaka.relativeFromNow(now.minusSeconds(10)))
        assertEquals("5 min ago", TimeDhaka.relativeFromNow(now.minusSeconds(300)))
        assertEquals("2 h ago", TimeDhaka.relativeFromNow(now.minusSeconds(7200)))
        assertTrue(TimeDhaka.relativeFromNow(now.plusSeconds(120)).startsWith("in "))
    }

    @Test
    fun parsesVendorSlotInput() {
        assertEquals(LocalTime.of(9, 0), TimeDhaka.parseHhMm("09:00"))
        assertEquals(1350, TimeDhaka.minutesFromHhMm("22:30"))
        assertEquals(null, TimeDhaka.parseHhMm("25:00"))
    }

    @Test
    fun readsASlotTheWayTheOwnerTypedIt() {
        // A single-digit hour and a Bangla keyboard's digits are the same 9:30 the
        // Worker reads; refusing them made the slot vanish from the phone only.
        assertEquals(LocalTime.of(9, 0), TimeDhaka.parseHhMm("9:00"))
        assertEquals(LocalTime.of(9, 30), TimeDhaka.parseHhMm("০৯:৩০"))
        assertEquals(LocalTime.of(21, 15), TimeDhaka.parseHhMm(" 21:15:00 "))
        assertEquals("9:00 AM", TimeDhaka.prettyHhMm("9:00"))
        assertEquals(null, TimeDhaka.parseHhMm("9.30"))
        assertEquals(null, TimeDhaka.parseHhMm("12:60"))
    }
}
