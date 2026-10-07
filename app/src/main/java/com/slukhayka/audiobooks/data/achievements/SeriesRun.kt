package com.slukhayka.audiobooks.data.achievements

/**
 * #701 — one recorded completion with the series its Work belongs to.
 *
 * The pair (book, time) is what a RUN needs: `observeCompletionTimes` returns
 * timestamps alone, and a timestamp without a book cannot tell a relisten of
 * one volume from the next volume of the series.
 */
data class SeriesCompletion(val bookId: String, val timestamp: Long, val seriesTitle: String)

/**
 * #701 — «П'ять поспіль» (spec story 35), read literally: **five consecutive
 * completions in time that belong to Works of ONE series**.
 *
 * Deliberately NOT «п'ять томів за порядком номерів»: the order of volumes
 * would need the series SIZE, and the database has none — `series_members` is
 * written only for the Work someone actually opened, and `series` carries no
 * size column. A number the data cannot prove is not a number an award may use
 * (ADR-0014).
 *
 * The run is counted in DISTINCT books: hearing the same volume twice is a
 * relisten, not a second volume, so it must not bring the award closer. The
 * book is the unit every other completion count in this module already uses
 * (`COUNT(DISTINCT bookId)` in `observeCompletedBooks`).
 */
object SeriesRun {
    fun longest(completions: List<SeriesCompletion>): Long {
        // The rule is about TIME, so the order is established here instead of
        // being trusted from the caller; equal timestamps keep the incoming
        // order (the DAO orders by `timestamp, id`).
        val ordered = completions.sortedBy { it.timestamp }
        var longest = 0L
        var currentSeries: String? = null
        var currentBooks = mutableSetOf<String>()
        for (completion in ordered) {
            if (completion.seriesTitle != currentSeries) {
                currentSeries = completion.seriesTitle
                currentBooks = mutableSetOf()
            }
            currentBooks += completion.bookId
            longest = maxOf(longest, currentBooks.size.toLong())
        }
        return longest
    }
}
