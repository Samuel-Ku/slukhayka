package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.duration.DurationBook
import com.slukhayka.audiobooks.data.duration.DurationBuckets

/**
 * spec-18 T3 (#114) — the Огляд «За тривалістю» rows, already bucketed by the
 * pure [DurationBuckets] module into full book entities for the cards.
 */
data class DurationBooks(
    val short: List<AudiobookEntity>,
    val long: List<AudiobookEntity>
)

/**
 * spec-18 T3 (#114) — the bucketing-and-join glue between the library and the
 * UI rows: feed every book to the pure [DurationBuckets] module, then map the
 * bucketed ids back to the full entities the cards render. Extracted from the
 * old ViewModel flow so the render contract ("rows show exactly the bucketed
 * books") is pinned by a JVM test.
 */
fun durationBooksFrom(books: List<AudiobookEntity>): DurationBooks {
    val byId = books.associateBy { it.id }
    val (short, long) = DurationBuckets.splitByDuration(
        books.map { DurationBook(it.id, it.totalDurationSeconds) }
    )
    return DurationBooks(
        short = short.mapNotNull { byId[it.id] },
        long = long.mapNotNull { byId[it.id] }
    )
}
