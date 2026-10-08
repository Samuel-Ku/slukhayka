package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.achievements.ListeningRhythm
import com.slukhayka.audiobooks.data.db.ListeningStatEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1168 — the stats card's three numbers at their exact boundaries.
 *
 * The card used to count ROWS: `takeWhile { it.listenedSeconds > 0 }` bridged
 * a silence and `take(7)` meant seven rows, not seven days. Every case below
 * is a claim the ticket made, so a later change has to break a named line
 * rather than slip through as drift.
 */
class ListeningStatsSummaryTest {

    private val minute = ListeningRhythm.DAY_MILLIS
    private val today = LocalDate.of(2026, 10, 7)

    private fun row(iso: String, millis: Long = 10 * minute) =
        ListeningStatEntity(dateIso = iso, listenedSeconds = millis / 1_000L, verifiedListenedMillis = millis)

    /** A row written before v51: the old seconds counter exists, no proof does. */
    private fun legacyRow(iso: String, seconds: Long = 12_000L) =
        ListeningStatEntity(dateIso = iso, listenedSeconds = seconds)

    private fun card(vararg rows: ListeningStatEntity) = ListeningStatsSummary.of(rows.toList(), today)

    @Test fun `a gap between dates ends the streak instead of being bridged`() {
        // The ticket's own example: 01 and 04 October are two runs of one day,
        // never «2 дні підряд». A row count said 2.
        assertEquals(1, card(row("2026-10-01"), row("2026-10-04")).streakDays)
        // Silence in the middle of an otherwise full week breaks it too, and
        // the run is the one ending at the newest day.
        assertEquals(2, card(row("2026-10-03"), row("2026-10-04"), row("2026-10-06"), row("2026-10-07")).streakDays)
    }

    @Test fun `adjacent dates give the full streak, across month and year boundaries`() {
        assertEquals(7, card(*(1..7).map { row("2026-10-0$it") }.toTypedArray()).streakDays)
        // 28-30 September + 1-4 October: a month boundary is not a break.
        assertEquals(7, card(
            row("2026-09-28"), row("2026-09-29"), row("2026-09-30"),
            row("2026-10-01"), row("2026-10-02"), row("2026-10-03"), row("2026-10-04")
        ).streakDays)
        // A year boundary is not a break either.
        val newYear = (0..6).map { row(LocalDate.of(2026, 12, 30).plusDays(it.toLong()).toString()) }
        assertEquals(7, card(*newYear.toTypedArray()).streakDays)
    }

    @Test fun `the week is seven CALENDAR days, not the seven newest rows`() {
        // 07 is today, so the window is 01-07 October. 30 September and
        // 10 September are outside it, though a `take(7)` would have summed
        // the first of them.
        val spread = card(
            row("2026-10-07"), row("2026-10-05"), row("2026-10-01"),
            row("2026-09-30"), row("2026-09-10")
        )
        assertEquals(3 * 10 * minute, spread.weekMillis)

        // Both edges: today minus six days is inside, today minus seven is not,
        // and neither is a day that has not happened yet.
        assertEquals(10 * minute, card(row("2026-10-01")).weekMillis)
        assertEquals(0L, card(row("2026-09-30")).weekMillis)
        assertEquals(0L, card(row("2026-10-08")).weekMillis)

        // Eight days in a row still make one week: the eighth falls out.
        val eightDays = (0..7).map { row(today.minusDays(it.toLong()).toString()) }
        assertEquals(7 * 10 * minute, ListeningStatsSummary.of(eightDays, today).weekMillis)
    }

    @Test fun `a day below one honest minute is not a day`() {
        assertEquals("59 999 ms is not a day", 0, card(row("2026-10-07", 59_999L)).streakDays)
        assertEquals("60 000 ms is a day", 1, card(row("2026-10-07", 60_000L)).streakDays)
        // And a stray half-minute cannot hold two real days together.
        assertEquals(1, card(row("2026-10-05"), row("2026-10-06", 30_000L), row("2026-10-07")).streakDays)
    }

    @Test fun `rows written before v51 are not a day of listening`() {
        val onlyLegacy = ListeningStatsSummary.of(listOf(legacyRow("2026-10-07")), today)
        assertEquals(0L, onlyLegacy.todayMillis)
        assertEquals(0L, onlyLegacy.weekMillis)
        assertEquals(0, onlyLegacy.streakDays)

        // A legacy row between two real days is silence, not a bridge.
        assertEquals(1, card(row("2026-10-05"), legacyRow("2026-10-06"), row("2026-10-07")).streakDays)
    }

    @Test fun `the card reads verified milliseconds, not the old seconds counter`() {
        // The old counter lies by a wide margin here; only the verified one is
        // proof, so it wins.
        val disagreeing = ListeningStatEntity(
            dateIso = today.toString(),
            listenedSeconds = 999L,
            verifiedListenedMillis = 120_000L
        )
        assertEquals(120_000L, ListeningStatsSummary.of(listOf(disagreeing), today).todayMillis)
        // The summary hands the proof over untouched; the tile floors it to
        // whole minutes itself.
        assertEquals(59_999L, card(row(today.toString(), 59_999L)).todayMillis)
    }

    @Test fun `the window sums honest time even from a day too short for a streak`() {
        // The streak ignores a 30-second day; the hours tile does not, because
        // those seconds were really played (ADR-0014: numbers stay honest).
        val halfMinute = card(row("2026-10-06", 30_000L))
        assertEquals(0, halfMinute.streakDays)
        assertEquals(30_000L, halfMinute.weekMillis)
    }

    @Test fun `an unreadable date is skipped rather than guessed`() {
        assertEquals(1, card(row("07.10.2026"), row("2026-10-07")).streakDays)
        assertEquals(10 * minute, card(row("07.10.2026"), row("2026-10-07")).weekMillis)
    }

    @Test fun `the card does not depend on the order the rows arrive in`() {
        val week = (1..7).map { row("2026-10-0$it") }
        assertEquals(
            ListeningStatsSummary.of(week, today),
            ListeningStatsSummary.of(week.reversed(), today)
        )
        // One date is one day, however many rows claim it (dateIso is a
        // primary key, so this only guards a future shape).
        assertEquals(1, card(row("2026-10-07"), row("2026-10-07")).streakDays)
    }

    @Test fun `an empty history is an empty card`() {
        assertEquals(ListeningStatsSummary.Card(0L, 0L, 0), ListeningStatsSummary.of(emptyList(), today))
    }

    @Test fun `the threshold is the one the regularity awards use`() {
        // Two readers, one number: #1166 pinned it, the card must not drift
        // into a second «day with listening».
        assertEquals(60_000L, ListeningRhythm.DAY_MILLIS)
    }
}
