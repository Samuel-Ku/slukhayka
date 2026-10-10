package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.collections.CollectionList
import com.slukhayka.audiobooks.data.collections.CollectionMatcher

/**
 * #701 (US38) — «Колекціонер»: how many curated collections the listener has
 * passed ENTIRELY.
 *
 * A collection is passed when EVERY curated entry is covered by an own library
 * book the listener finished. Both halves are the owner's reading (#701):
 *
 *  - the composition comes from the curated asset, so "every entry" is a real,
 *    knowable set — unlike a series, whose full membership exists only online.
 *    Reading it any other way (for instance "the entries the listener happens
 *    to own") would pass a whole collection the moment one matching book was
 *    finished, which is exactly the fabricated progress ADR-0014 forbids;
 *  - the progress is read from OWN records only (EXPLICIT_SAVE /
 *    EXPLICIT_IMPORT, ADR-0060) and from a recorded end-of-book event, so a
 *    catalogue mirror or a hand-set «Прослухано» can never claim a collection.
 *
 * An entry covered by several own copies is covered ONCE: a listener who
 * finished one rendition of «Життя Пі» has heard the book, and owning an
 * unfinished second copy does not take that back. An empty collection is not
 * passed — there is nothing in it to pass.
 *
 * The lists arrive as data ([CollectionList], the `collections/` assets)
 * rather than being loaded here, so a test can pin a two-entry collection
 * without inventing one in the shipped assets.
 */
object CollectionCompletion {

    fun passed(books: List<OwnLibraryBook>, collections: List<CollectionList>): Long =
        collections.count { collection ->
            collection.entries.isNotEmpty() && collection.entries.all { entry ->
                books.any { book ->
                    book.completedAt != null &&
                        CollectionMatcher.entryMatches(entry, book.title, book.author)
                }
            }
        }.toLong()
}
