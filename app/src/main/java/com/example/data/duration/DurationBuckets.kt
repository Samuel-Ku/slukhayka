package com.example.data.duration

/**
 * spec-18 (#112) — the pure short/long bucketing for the Огляд
 * «За тривалістю» section. No Android, network, or database dependencies:
 * the repository and the ViewModel both feed plain [DurationBook] values in
 * and read [DurationRows] out.
 *
 * Honest-data doctrine: only books with a known duration are ever surfaced.
 * The middle band (5 h .. under 10 h) is deliberately not surfaced. The
 * fabricated legacy value 14400 s is deliberately NOT accepted (see
 * [FABRICATED_LEGACY_SECONDS]) — the row filter is the single honest-data
 * gate, and it can only see the stored column, so a real 4:00:00 book shares
 * the sentinel's one bit and stays hidden until a different real duration
 * arrives. That residue is accepted: dropping the sentinel would surface
 * every guessed 4:00:00 as fact.
 */
object DurationBuckets {

    /** «Короткі» = strictly under 5 hours. */
    const val SHORT_ROW_MAX_SECONDS = 5 * 60 * 60

    /** «Довгі» = 10 hours and up. */
    const val LONG_ROW_MIN_SECONDS = 10 * 60 * 60

    /**
     * The fabricated legacy seed duration (4:00:00 = 14400 s) older catalogue
     * rows carried. Treated as unknown wherever a duration is asked for —
     * the value never renders as real.
     */
    const val FABRICATED_LEGACY_SECONDS = 14_400L

    /**
     * Whether a total duration is honest enough to surface. Positive and not
     * the fabricated legacy placeholder.
     */
    fun hasKnownDuration(totalDurationSeconds: Long): Boolean =
        totalDurationSeconds > 0L && totalDurationSeconds != FABRICATED_LEGACY_SECONDS

    /**
     * Splits books with a known duration into the short row (under 5 h) and
     * the long row (10 h and up); everything else is dropped. Input order is
     * preserved inside each row.
     */
    fun splitByDuration(books: List<DurationBook>): DurationRows {
        val short = ArrayList<DurationBook>()
        val long = ArrayList<DurationBook>()
        for (book in books) {
            if (!hasKnownDuration(book.totalDurationSeconds)) continue
            when {
                book.totalDurationSeconds < SHORT_ROW_MAX_SECONDS -> short.add(book)
                book.totalDurationSeconds >= LONG_ROW_MIN_SECONDS -> long.add(book)
            }
        }
        return DurationRows(short, long)
    }
}

/** One candidate for a duration row: identity + the book's total duration. */
data class DurationBook(
    val id: String,
    val totalDurationSeconds: Long
)

/** The two surfaced rows; either may be empty. */
data class DurationRows(
    val short: List<DurationBook>,
    val long: List<DurationBook>
)