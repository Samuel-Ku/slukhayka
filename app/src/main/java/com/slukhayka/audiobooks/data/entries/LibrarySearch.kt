package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookEntity

/**
 * #737 / ADR-0041 — the offline, library-first half of search: the
 * listener's own Медіатека rows matched by title or author, instantly and
 * with zero requests. The live Source Catalog results render BELOW these,
 * never above, so a book the listener owns is always the first hit — and an
 * owned book is still findable with the network down.
 *
 * A blank query is not a search: it returns the whole library (the screen
 * keeps its previous behaviour of showing every row when the field is
 * empty).
 */
fun List<AudiobookEntity>.matchingLibraryQuery(query: String): List<AudiobookEntity> {
    val clean = query.trim()
    if (clean.isEmpty()) return this
    return filter { book ->
        book.title.contains(clean, ignoreCase = true) ||
            book.author.contains(clean, ignoreCase = true)
    }
}
