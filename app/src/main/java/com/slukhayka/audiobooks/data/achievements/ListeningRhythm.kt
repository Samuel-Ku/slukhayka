package com.slukhayka.audiobooks.data.achievements

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * #1166 (T8) — the SHAPE of listening across days, decided as pure arithmetic.
 *
 * `listening_stats` keeps one row per day the listener actually listened, with
 * the monotonic verified time of that day. Everything the regularity awards ask
 * about is a property of that SEQUENCE, not of any single row, so it is decided
 * here from a plain list: no Room, no clock, no zone, and nothing that depends
 * on the moment the question is asked.
 *
 * A day without listening has NO row at all (`AudiobookDao.addVerifiedListeningTime`
 * returns early on zero millis), so «seven days in a row» has to be proved by
 * adjacency of DATES. Counting consecutive rows would silently bridge a
 * three-day silence — that mistake already lives in the library stats card, and
 * repeating it here would make the award claim a week the listener never had.
 *
 * Every number below is MONOTONE: a best day, a longest streak, a fullest month
 * and a count of Mondays can only grow. An award therefore cannot be taken back
 * by a quiet week or by the calendar rolling over.
 */
object ListeningRhythm {

    /** One day the listener really listened, in their own local calendar. */
    data class Day(val date: LocalDate, val verifiedMillis: Long)

    /**
     * What counts as «a day with listening».
     *
     * One honest minute. The player checkpoints verified millis about once a
     * second, and a stray second is not a day of listening; rows written before
     * v51 carry `verifiedListenedMillis = 0` and therefore never qualify — the
     * old five-second counter is not proof (`ListeningStatEntity` says so).
     */
    const val DAY_MILLIS = 60_000L

    /** The listener's single best day. */
    fun bestDayMillis(days: List<Day>): Long = days.maxOfOrNull { it.verifiedMillis } ?: 0L

    /**
     * Longest run of ADJACENT qualifying days, ever.
     *
     * The run requires `previous.plusDays(1)`: a missing date ENDS the run
     * instead of being skipped over.
     */
    fun longestStreak(days: List<Day>): Int {
        val dates = qualifying(days).map { it.date }.sorted()
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (date in dates) {
            run = if (previous != null && date == previous.plusDays(1)) run + 1 else 1
            if (run > best) best = run
            previous = date
        }
        return best
    }

    /**
     * Most qualifying days inside ONE calendar month, ever.
     *
     * A calendar month, not a rolling thirty days: «Місяць у навушниках» says
     * «у місяці», and days from two neighbouring months are not days of one.
     */
    fun bestMonthDays(days: List<Day>): Int = qualifying(days)
        .groupingBy { YearMonth.from(it.date) }
        .eachCount()
        .maxOfOrNull { it.value } ?: 0

    /** How many different Mondays carried listening, ever. */
    fun mondays(days: List<Day>): Int = qualifying(days).count { it.date.dayOfWeek == DayOfWeek.MONDAY }

    /**
     * #1166 (T8) — «Слухацький рік» (story 13): how many DIFFERENT days with
     * listening, ever.
     *
     * CUMULATIVE, never one calendar year. The spec words the award as «за
     * рік», but read literally that year is both unreachable and non-monotone:
     * a listener who heard a book every day for two years would still start
     * from zero every 1 January, and an earned award could be taken back by the
     * calendar. The owner's decision (#1166) is the running count of distinct
     * days, so this number only ever grows.
     *
     * The unit is the day, and the honesty bar is the one every regularity
     * award here already reads ([DAY_MILLIS]): a sub-minute day and a pre-v51
     * row with zero verified millis are rows, not days of listening, and
     * counting ROWS instead would hand out the year early.
     */
    fun listeningDays(days: List<Day>): Int = qualifying(days).size

    /**
     * The days that count, each date ONCE.
     *
     * The one-row-per-day invariant belongs here rather than in one caller:
     * `dateIso` is a primary key today, but every metric below reads the same
     * sequence, and a second reader (the library stats card, #1168) must not
     * inherit a double count from a shape this object did not promise.
     */
    private fun qualifying(days: List<Day>): List<Day> =
        days.filter { it.verifiedMillis >= DAY_MILLIS }.distinctBy { it.date }
}
