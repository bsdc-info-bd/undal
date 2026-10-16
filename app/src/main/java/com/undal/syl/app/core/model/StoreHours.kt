package com.undal.syl.app.core.model

import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalDigits
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * When the kitchen is open — the one rule, in Kotlin.
 *
 * The twin of `worker/src/util/store-hours.ts`: the web page, the web checkout
 * and the presence cron ask that file, the phone asks this one, and
 * `StoreHoursTest` holds both to the same fixture week and the same answers.
 *
 * **The schedule is the authority on *when*; the owner's switch decides
 * *whether*.** A store takes orders when, and only when, the switch is on, the
 * watchdog has not paused it, and the published hours cover this minute. The
 * switch used to override the hours instead, and that is how a kitchen that
 * published 09:00–22:00 came to take orders at three in the morning: the owner
 * opened once, and nothing ever closed it.
 *
 * Pure on purpose — no Android, no Firebase — so it is tested on the JVM.
 */
object StoreHours {

    /** Minutes in a day: the closing minute of a window that runs to midnight. */
    const val END_OF_DAY: Int = 24 * 60

    /** One slot as minutes: `open` in 0…1439, `close` in 1…1440, `close < open` crosses midnight. */
    data class Window(val open: Int, val close: Int) {
        val crossesMidnight: Boolean get() = close < open
    }

    /** The next opening: how many days ahead, at what minute, and the words for it. */
    data class NextOpening(val dayOffset: Int, val minutes: Int, val date: LocalDate) {

        /** "today at 7:00 PM", "tomorrow at 9:00 AM", "on Saturday at 9:00 AM". */
        val label: String
            get() {
                val day = when (dayOffset) {
                    0 -> "today"
                    1 -> "tomorrow"
                    else -> "on ${TimeDhaka.weekdayLabel(date)}"
                }
                return "$day at ${prettyMinutes(minutes)}"
            }
    }

    private val CLOCK_TEXT = Regex("^(\\d{1,2}):(\\d{2})$")

    /**
     * "09:00", "9:00" or "০৯:০০" → minutes since midnight; null for anything that
     * is not a clock time. "24:00" is accepted and means the end of the day.
     *
     * Strict on purpose: a time nobody can read must not quietly become a slot.
     * `TimeDhaka.parseHhMm` refused "9:00" and Bengali digits outright, so a slot
     * typed on a Bangla keyboard vanished from the phone's schedule while the
     * Worker still honoured it.
     */
    fun clockMinutes(value: String?): Int? {
        val text = UndalDigits.toAscii(value?.trim() ?: return null)
        val match = CLOCK_TEXT.matchEntire(text) ?: return null
        val hours = match.groupValues[1].toInt()
        val minutes = match.groupValues[2].toInt()
        if (minutes > 59) return null
        if (hours == 24 && minutes == 0) return END_OF_DAY
        if (hours > 23) return null
        return hours * 60 + minutes
    }

    /**
     * A slot as a window, or null when it cannot be read. A close of "00:00",
     * "23:59" or "24:00" means "until midnight": the 24/7 template writes 23:59,
     * and a store must not close for the last minute of every day because of it.
     */
    fun window(slot: StoreSlot): Window? {
        val open = clockMinutes(slot.open) ?: return null
        val rawClose = clockMinutes(slot.close) ?: return null
        if (open >= END_OF_DAY || rawClose == open) return null
        val close = if (rawClose == 0 || rawClose == END_OF_DAY - 1 || rawClose == END_OF_DAY) END_OF_DAY else rawClose
        if (close == open) return null
        return Window(open, close)
    }

    /** "fri", "Fri", "friday" and "FRIDAY" are all Friday. */
    private fun weekdayOf(key: String): String = key.trim().lowercase(java.util.Locale.ENGLISH).take(3)

    private fun weeklySlots(config: StoreConfig, date: LocalDate): List<StoreSlot> {
        val weekday = TimeDhaka.weekdayKey(date)
        return config.weekly.entries.firstOrNull { (key, _) -> weekdayOf(key) == weekday }?.value.orEmpty()
    }

    fun specialOn(config: StoreConfig, date: LocalDate): StoreSpecialDate? {
        val key = TimeDhaka.dateKey(date)
        return config.specialDates.firstOrNull { it.date == key }
    }

    /**
     * The slots that apply on one Dhaka date: a day off has none, a special date
     * with hours has its own, and everything else — including a special date
     * marked open with no hours, which the schedule builder tells the owner
     * "behaves like a normal day" — has the weekly ones.
     */
    fun slotsOn(config: StoreConfig, date: LocalDate): List<StoreSlot> {
        val special = specialOn(config, date)
        if (special != null) {
            if (special.closed) return emptyList()
            if (special.slots.isNotEmpty()) return special.slots
        }
        return weeklySlots(config, date)
    }

    private fun windowsOn(config: StoreConfig, date: LocalDate): List<Window> =
        slotsOn(config, date).mapNotNull { window(it) }

    /**
     * Do the published hours cover this moment?
     *
     * Today's windows from their opening minute, plus the after-midnight tail of
     * *yesterday's* windows that cross midnight. A Friday 20:00–01:00 service is
     * open at 00:30 on Saturday even when Saturday is a day off.
     */
    fun isOpenAt(config: StoreConfig, now: ZonedDateTime = TimeDhaka.now()): Boolean {
        val local = now.withZoneSameInstant(TimeDhaka.zone)
        val today = local.toLocalDate()
        val minutes = TimeDhaka.minutesOfDay(local.toLocalTime())
        val inToday = windowsOn(config, today).any { window ->
            if (window.crossesMidnight) minutes >= window.open else minutes >= window.open && minutes < window.close
        }
        if (inToday) return true
        return windowsOn(config, today.minusDays(1)).any { window -> window.crossesMidnight && minutes < window.close }
    }

    /** The next time a window opens, searched over the coming week; null if none does. */
    fun nextOpening(config: StoreConfig, now: ZonedDateTime = TimeDhaka.now()): NextOpening? {
        val local = now.withZoneSameInstant(TimeDhaka.zone)
        val minutesNow = TimeDhaka.minutesOfDay(local.toLocalTime())
        for (offset in 0..7) {
            val date = local.toLocalDate().plusDays(offset.toLong())
            val first = windowsOn(config, date)
                .map { it.open }
                .filter { offset > 0 || it > minutesNow }
                .minOrNull()
                ?: continue
            return NextOpening(dayOffset = offset, minutes = first, date = date)
        }
        return null
    }

    /** When the window that is open right now closes, as "10:00 PM" or "midnight"; null when closed. */
    fun closingLabel(config: StoreConfig, now: ZonedDateTime = TimeDhaka.now()): String? {
        val local = now.withZoneSameInstant(TimeDhaka.zone)
        val today = local.toLocalDate()
        val minutes = TimeDhaka.minutesOfDay(local.toLocalTime())
        windowsOn(config, today).forEach { window ->
            if (!window.crossesMidnight && minutes >= window.open && minutes < window.close) {
                return if (window.close == END_OF_DAY) "midnight" else prettyMinutes(window.close)
            }
            if (window.crossesMidnight && minutes >= window.open) return prettyMinutes(window.close)
        }
        windowsOn(config, today.minusDays(1)).forEach { window ->
            if (window.crossesMidnight && minutes < window.close) return prettyMinutes(window.close)
        }
        return null
    }

    /** True when any weekday, or any special date, publishes a usable window. */
    fun hasAnyHours(config: StoreConfig): Boolean =
        config.weekly.values.any { slots -> slots.any { window(it) != null } } ||
            config.specialDates.any { special -> !special.closed && special.slots.any { window(it) != null } }

    /** Is the store taking orders at this moment? The one question every screen asks. */
    fun isTakingOrders(config: StoreConfig, now: ZonedDateTime = TimeDhaka.now()): Boolean =
        config.switchOn && !config.autoClosed && isOpenAt(config, now)

    /** "9:00 AM", the form `TimeDhaka.prettyHhMm` prints. */
    fun prettyMinutes(minutes: Int): String {
        val wrapped = ((minutes % END_OF_DAY) + END_OF_DAY) % END_OF_DAY
        val hours = wrapped / 60
        val twelve = if (hours % 12 == 0) 12 else hours % 12
        val suffix = if (hours < 12) "AM" else "PM"
        return "$twelve:${(wrapped % 60).toString().padStart(2, '0')} $suffix"
    }
}
