package com.slukhayka.audiobooks.data.achievements

/**
 * #701 — one recorded completion with the series its Work belongs to.
 *
 * The pair (book, time) is what a RUN needs: `observeCompletionTimes` returns
 * timestamps alone, and a timestamp without a book cannot tell a relisten of
 * one volume from the next volume of the series. [seriesTitle] is null when the
 * data cannot name a series at all — that is a fact about the run, not a row to
 * skip.
 */
data class SeriesCompletion(val bookId: String, val timestamp: Long, val seriesTitle: String?)

/**
 * #701 — «П'ять поспіль» (spec story 35), read literally: **five consecutive
 * completions in time that belong to Works of ONE series**.
 *
 * Deliberately NOT «п'ять томів за порядком номерів» (`works.seriesIndex`): the
 * numbering exists, but it is INCOMPLETE and unproven — a source may leave it
 * empty, and nothing in the database says which numbers the series really has.
 * Ordering by a numbering the data cannot vouch for would be a guess, while a
 * run of consecutive completions in time is a fact the events carry themselves
 * (ADR-0014). The series SIZE is a different need — story 36 «По порядку» —
 * and this award does not rest on it.
 *
 * A completion the data cannot tie to a series — no Work row, a NULL, empty or
 * whitespace-only title — BREAKS the run: it happened between volumes, so those
 * volumes are not «підряд». Such a completion is never skipped and never counts
 * for anything itself.
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
            // A trailing space is a typo in a claim, not another series. The
            // case is deliberately left alone — folding it would be a different
            // decision, and not this award's.
            val series = completion.seriesTitle?.trim()?.takeIf(String::isNotEmpty)
            if (series == null) {
                currentSeries = null
                currentBooks = mutableSetOf()
                continue
            }
            if (series != currentSeries) {
                currentSeries = series
                currentBooks = mutableSetOf()
            }
            currentBooks += completion.bookId
            longest = maxOf(longest, currentBooks.size.toLong())
        }
        return longest
    }
}
