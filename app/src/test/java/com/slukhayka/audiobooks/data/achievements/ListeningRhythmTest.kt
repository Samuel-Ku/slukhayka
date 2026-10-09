package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.achievements.ListeningRhythm.Day
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1166 (T8) — the shape of listening across days, at its exact boundaries.
 *
 * Table-driven on purpose: every threshold here was chosen by the ticket (the
 * spec names the awards, not the numbers), so a later change has to break a
 * named row rather than slip through as drift.
 */
class ListeningRhythmTest {

    private val minute = ListeningRhythm.DAY_MILLIS

    private fun day(iso: String, millis: Long = 10 * minute): Day = Day(LocalDate.parse(iso), millis)

    @Test fun `best day is the largest single day, and an empty history has none`() {
        assertEquals(0L, ListeningRhythm.bestDayMillis(emptyList()))
        assertEquals(3 * 3_600_000L, ListeningRhythm.bestDayMillis(listOf(
            day("2026-10-01", 600_000L),
            day("2026-10-02", 3 * 3_600_000L),
            day("2026-10-03", minute)
        )))
    }

    @Test fun `a streak counts ADJACENT dates and a missing date ends it`() {
        val cases = listOf(
            Triple("empty history", emptyList<Day>(), 0),
            Triple("one day", listOf(day("2026-10-01")), 1),
            // The whole point: 01 and 04 are two separate runs of one, never a
            // run of two. A row count would have said "2 days in a row".
            Triple("silence between two days", listOf(day("2026-10-01"), day("2026-10-04")), 1),
            Triple("six days is not seven", (1..6).map { day("2026-10-0$it") }, 6),
            Triple("seven days in a row", (1..7).map { day("2026-10-0$it") }, 7),
            // 28-30 September + 1-4 October: a month boundary is not a break.
            Triple("a week across a month boundary", listOf(
                "2026-09-28", "2026-09-29", "2026-09-30",
                "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04"
            ).map { day(it) }, 7),
            // A year boundary is not a break either.
            Triple("a week across a year boundary",
                (0..6).map { day(LocalDate.of(2026, 12, 30).plusDays(it.toLong()).toString()) }, 7),
            // A day below the honesty threshold is not a day at all, so it
            // cannot hold a run together either.
            Triple("a stray second does not bridge a streak", listOf(
                day("2026-10-01"), day("2026-10-02", 30_000L), day("2026-10-03")
            ), 1),
            Triple("a duplicate date cannot lengthen a run", listOf(
                day("2026-10-01"), day("2026-10-01"), day("2026-10-02")
            ), 2)
        )
        for ((name, days, streak) in cases) {
            assertEquals(name, streak, ListeningRhythm.longestStreak(days))
        }
    }

    @Test fun `the fullest month is one calendar month, never two added together`() {
        val march = (1..10).map { day("2026-03-%02d".format(it)) }
        val april = (1..10).map { day("2026-04-%02d".format(it)) }
        assertEquals("ten plus ten is ten, not twenty", 10, ListeningRhythm.bestMonthDays(march + april))
        assertEquals(11, ListeningRhythm.bestMonthDays(march + april + day("2026-04-11")))
        // December and January are two months, however close they look.
        val december = (1..10).map { day("2026-12-%02d".format(it)) }
        val january = (1..10).map { day("2027-01-%02d".format(it)) }
        assertEquals("a year boundary is still a month boundary", 10, ListeningRhythm.bestMonthDays(december + january))
        assertEquals("nineteen days is not twenty", 19, ListeningRhythm.bestMonthDays((1..19).map { day("2026-03-%02d".format(it)) }))
        assertEquals(20, ListeningRhythm.bestMonthDays((1..20).map { day("2026-03-%02d".format(it)) }))
    }

    @Test fun `Mondays are counted by weekday and only when the day was really heard`() {
        val monday = LocalDate.of(2026, 1, 5)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        val nineMondays = (0 until 9).map { day(monday.plusWeeks(it.toLong()).toString()) }
        assertEquals(9, ListeningRhythm.mondays(nineMondays))
        assertEquals(10, ListeningRhythm.mondays(nineMondays + day(monday.plusWeeks(9).toString())))
        // Tuesday is not a Monday, and a quiet Monday is not a Monday either.
        assertEquals(1, ListeningRhythm.mondays(listOf(
            day(monday.toString()), day(monday.plusDays(1).toString()), day(monday.plusWeeks(1).toString(), 30_000L)
        )))
    }

    /**
     * #1166 (T8, story 13) — «Слухацький рік»: 365 DIFFERENT days with
     * listening, cumulatively.
     *
     * Table-driven like every other threshold here. The interesting rows are
     * the two that are NOT days — a pre-v51 row with zero verified millis and a
     * stray half-minute. Both are rows in `listening_stats`, so an
     * implementation counting rows instead of qualifying days would report 367
     * where the listener really heard 365 days.
     *
     * One case deliberately CROSSES 1 January: 200 days of 2026 plus 165 of
     * 2027 are 365 different days and no calendar year owns more than 200 of
     * them. A fixture that lived inside one year could not tell the cumulative
     * reading from «most days in a year» or from a rolling 365-day window; this
     * one fails for both.
     */
    @Test fun `the listening year counts DIFFERENT qualifying days, cumulatively`() {
        val year = (0 until 365).map { day(LocalDate.of(2026, 1, 1).plusDays(it.toLong()).toString()) }
        val acrossNewYear = (0 until 200).map { day(LocalDate.of(2026, 6, 1).plusDays(it.toLong()).toString()) } +
            (0 until 165).map { day(LocalDate.of(2027, 1, 1).plusDays(it.toLong()).toString()) }
        val cases = listOf(
            Triple("empty history", emptyList<Day>(), 0),
            Triple("a stray second is not a day", listOf(day("2026-10-01", 30_000L)), 0),
            Triple("a pre-v51 row is not a day either", listOf(day("2026-10-01", 0L)), 0),
            Triple("364 days is not a year", year.take(364), 364),
            Triple("365 different days is a year", year, 365),
            Triple("200 days of one year plus 165 of the next are 365", acrossNewYear, 365),
            Triple(
                "legacy and stray rows never add a day",
                year + listOf(day("2027-01-01", 0L), day("2027-01-02", 30_000L)),
                365
            ),
            Triple("a duplicate date is still one day", listOf(day("2026-10-01"), day("2026-10-01")), 1)
        )
        for ((name, days, count) in cases) {
            assertEquals(name, count, ListeningRhythm.listeningDays(days))
        }
    }
}
