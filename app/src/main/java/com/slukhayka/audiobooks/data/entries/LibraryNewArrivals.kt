package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.sourceIdForUrl

/**
 * ADR-0041 / #733 — one card of the library-first «Новинки» rail: a Work the
 * listener actually owns, its representative Library Entry, and the source
 * badge that entry came from. The rail reads the Медіатека, never a live
 * Source Catalog, so it works offline and changes the moment an entry is
 * imported or removed.
 */
data class LibraryNewArrival(
    /** Work identity (`workId`, else `mergeKey`, else the book id) — one card per Work. */
    val workKey: String,
    /** The representative (most recently added) Library Entry of the Work. */
    val book: AudiobookEntity,
    /** Registry display name of the entry's source («Локальна» for local imports). */
    val sourceName: String,
    /** When the representative entry entered the library (wayfinder #39). */
    val addedAt: Long
)

/**
 * #733 — the library-derived «Новинки» projection.
 *
 * Pure and deterministic so the rail's composition is unit-testable without
 * Compose: dedupe by Work (several narrations of one Work never duplicate the
 * rail), keep the most recently added entry as the card, sort newest-first
 * with a stable title tie-break, and cap the rail.
 */
object LibraryNewArrivals {

    /** The rail is a shelf, not the library: 20 newest Works is the honest cap. */
    const val DEFAULT_LIMIT = 20

    fun project(
        books: List<AudiobookEntity>,
        limit: Int = DEFAULT_LIMIT
    ): List<LibraryNewArrival> {
        if (books.isEmpty() || limit <= 0) return emptyList()
        return books
            .groupBy(::workKeyOf)
            .mapValues { (_, members) -> members.maxByOrNull { it.createdAt } ?: members.first() }
            .map { (workKey, newest) ->
                LibraryNewArrival(
                    workKey = workKey,
                    book = newest,
                    sourceName = sourceDisplayName(sourceIdForUrl(newest.sourceUrl)),
                    addedAt = newest.createdAt
                )
            }
            .sortedWith(
                compareByDescending<LibraryNewArrival> { it.addedAt }
                    .thenBy { it.book.title.lowercase() }
                    .thenBy { it.workKey }
            )
            .take(limit)
    }

    /**
     * One Work, one card: the Works anchor when the entry has one, else the
     * title|author merge identity, else the book's own id (local/blank-key
     * imports that never got a Works row).
     */
    private fun workKeyOf(book: AudiobookEntity): String =
        book.workId?.takeIf { it.isNotBlank() }
            ?: book.mergeKey.takeIf { it.isNotBlank() }
            ?: book.id
}
