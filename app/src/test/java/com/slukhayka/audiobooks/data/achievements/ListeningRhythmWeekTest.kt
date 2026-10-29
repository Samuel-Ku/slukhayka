package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.achievements.ListeningRhythm.Day
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F18 — the two questions the «Досягнення» screen's week strip asks of the day
 * sequence, pinned without a clock, a zone or a Room.
 *
 * The dates are REAL weekdays and the test says so: 2026-10-26 is a Monday and
 * 2026-10-28 a Wednesday, so "the first cell is Monday" cannot drift into
 * "whatever the calendar says today" if a later change swaps the week start.
 */
class ListeningRhythmWeekTest {

    private val minute = ListeningRhythm.DAY_MILLIS

    /** 2026-10-28 — a Wednesday, a third of the way through its own week. */
    private val today = LocalDate.of(2026, 10, 28)

    private fun day(iso: String, millis: Long = 10 * minute): Day = Day(LocalDate.parse(iso), millis)

    // --- the parse ---------------------------------------------------------

    @Test
    fun `a stored row becomes a day, and a broken date becomes nothing`() {
        assertEquals(Day(LocalDate.of(2026, 10, 28), 5 * minute), ListeningRhythm.of("2026-10-28", 5 * minute))
        assertNull(ListeningRhythm.of("2026-13-01", 5 * minute))
        assertNull(ListeningRhythm.of("", 5 * minute))
    }

    // --- the week ----------------------------------------------------------

    @Test
    fun `the week is Monday first, and the dates are real weekdays`() {
        assertEquals(java.time.DayOfWeek.WEDNESDAY, today.dayOfWeek)

        val flags = ListeningRhythm.weekFlags(
            listOf(day("2026-10-26"), day("2026-10-28")),
            today,
        )

        assertEquals(7, flags.size)
        // Monday the 26th and Wednesday the 28th carried listening; nothing else did.
        assertEquals(listOf(true, false, true, false, false, false, false), flags)
    }

    @Test
    fun `days after today are false even when a row claims them`() {
        // A wrong device clock once wrote a future row; it is not evidence of
        // listening that has not happened yet.
        val flags = ListeningRhythm.weekFlags(listOf(day("2026-10-29"), day("2026-10-30")), today)

        assertEquals(List(7) { false }, flags)
    }

    @Test
    fun `a sub-minute day is a row, not a day of listening`() {
        val flags = ListeningRhythm.weekFlags(
            listOf(day("2026-10-26", ListeningRhythm.DAY_MILLIS - 1), day("2026-10-28", ListeningRhythm.DAY_MILLIS)),
            today,
        )

        assertEquals(listOf(false, false, true, false, false, false, false), flags)
    }

    @Test
    fun `a day from last week is not in this week`() {
        // 2026-10-25 is a Sunday: the day before THIS Monday, i.e. last week.
        val flags = ListeningRhythm.weekFlags(listOf(day("2026-10-25")), today)

        assertEquals(List(7) { false }, flags)
    }

    // --- the run that is still alive --------------------------------------

    @Test
    fun `the current run counts adjacent days ending today`() {
        val days = listOf(day("2026-10-26"), day("2026-10-27"), day("2026-10-28"))

        assertEquals(3, ListeningRhythm.currentStreak(days, today))
    }

    @Test
    fun `a silent today does not end yesterday's run`() {
        // The listener has not started today yet; a streak that collapsed at
        // midnight would make the strip lie every morning.
        val days = listOf(day("2026-10-26"), day("2026-10-27"))

        assertEquals(2, ListeningRhythm.currentStreak(days, today))
    }

    @Test
    fun `two silent days end the run`() {
        val days = listOf(day("2026-10-24"), day("2026-10-25"))

        assertEquals(0, ListeningRhythm.currentStreak(days, today))
    }

    @Test
    fun `a gap inside the week only counts the tail`() {
        val days = listOf(day("2026-10-24"), day("2026-10-27"), day("2026-10-28"))

        assertEquals(2, ListeningRhythm.currentStreak(days, today))
    }

    @Test
    fun `an empty history has no run`() {
        assertEquals(0, ListeningRhythm.currentStreak(emptyList(), today))
        assertTrue(
            "an empty week is still seven cells for the screen",
            ListeningRhythm.weekFlags(emptyList(), today).size == 7,
        )
    }
}
