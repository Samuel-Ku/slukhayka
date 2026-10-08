package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.achievements.ListeningRhythm
import com.slukhayka.audiobooks.data.db.ListeningStatEntity
import java.time.LocalDate

/**
 * #1168 — what the Медіатека stats card says, decided as pure arithmetic.
 *
 * `listening_stats` keeps one row per day the listener really listened
 * (`AudiobookDao.addVerifiedListeningTime` returns early on zero millis), so
 * «днів підряд» and «за тиждень» are properties of the DATES in that sequence,
 * not of how many rows happen to exist. Counting rows — the old `takeWhile`
 * and `take(7)` — silently bridged a silence the listener actually had: rows
 * for 1 and 4 October claimed «2 дні підряд», though three days were quiet.
 *
 * The rows carry two counters. `listenedSeconds` is the old «once per five
 * seconds» approximation the entity itself calls not-proof; the honest one is
 * `verifiedListenedMillis`. Rows written before v51 keep it at zero and are
 * therefore not a day of listening at all — that is the same proof, and the
 * same threshold ([ListeningRhythm.DAY_MILLIS]), the regularity awards read
 * (#1166). Otherwise one listener would see two different weeks in one app.
 *
 * The only thing borrowed from Room is the row type itself; `today` arrives as
 * an argument, so a pure-JVM test can pin every boundary.
 */
object ListeningStatsSummary {

    /** «За тиждень» — today and the six calendar days before it. */
    const val WEEK_DAYS = 7L

    /**
     * The three numbers the card prints.
     *
     * @param todayMillis verified time of today itself;
     * @param weekMillis verified time inside the last [WEEK_DAYS] calendar days;
     * @param streakDays the run of ADJACENT days ending at the newest day that
     *   reached the threshold (never the longest run ever) — a missing date
     *   ends the run instead of being skipped over.
     */
    data class Card(val todayMillis: Long, val weekMillis: Long, val streakDays: Int)

    /** The three numbers for the [rows] the DAO handed over, read at [today]. */
    fun of(rows: List<ListeningStatEntity>, today: LocalDate): Card {
        // One date is one day, whatever order the rows arrive in (the old card
        // read them as the DAO happened to sort them).
        val verifiedByDate: Map<LocalDate, Long> = rows
            .mapNotNull { row -> dateOf(row)?.let { it to row.verifiedListenedMillis } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, millis) -> millis.sum() }
        val weekStart = today.minusDays(WEEK_DAYS - 1)
        return Card(
            todayMillis = verifiedByDate[today] ?: 0L,
            // Hours listened are counted honestly: every verified millisecond
            // inside the window, including a day too short to hold a streak.
            weekMillis = verifiedByDate.filterKeys { it in weekStart..today }.values.sum(),
            streakDays = streakEndingAtNewest(verifiedByDate, today)
        )
    }

    /**
     * The run of qualifying dates ending at the newest of them; 0 when none.
     *
     * The run ENDS at the newest day — it is not the longest run ever, which
     * is the regularity award's question (#1166). Days after [today] are not
     * history yet: the week window already stops at today, and a row dated
     * tomorrow (a device clock that jumped) must not anchor a streak either.
     */
    private fun streakEndingAtNewest(verifiedByDate: Map<LocalDate, Long>, today: LocalDate): Int {
        val days = verifiedByDate.filterValues { it >= ListeningRhythm.DAY_MILLIS }
            .keys.filter { it <= today }
        var date = days.maxOrNull() ?: return 0
        var run = 1
        while (days.contains(date.minusDays(1))) {
            date = date.minusDays(1)
            run++
        }
        return run
    }

    /**
     * A row whose date cannot be read is not a day we can count, so it is
     * skipped rather than guessed (ADR-0014) — the same rule the awards use.
     * `dateIso` is written as `yyyy-MM-dd`, so this drops only broken rows.
     */
    private fun dateOf(row: ListeningStatEntity): LocalDate? =
        runCatching { LocalDate.parse(row.dateIso) }.getOrNull()
}
