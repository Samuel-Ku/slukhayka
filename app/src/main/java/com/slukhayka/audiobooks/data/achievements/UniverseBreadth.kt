package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.universe.UniverseList
import com.slukhayka.audiobooks.data.universe.UniverseMatcher

/**
 * #701 (US37) — «Один всесвіт»: the most OWN books that belong to ONE curated
 * universe.
 *
 * A book belongs to a universe when its Work's series claim resolves to one of
 * the curated series ([UniverseMatcher] — the URL-first, title-alias rule the
 * universe surfaces themselves use), so the count rests on a real
 * bibliographic claim and never on a title that merely looks similar. A series
 * no curated universe knows contributes nothing at all.
 *
 * The unit is the WORK, not the library row: two renditions of one book are
 * one book of the universe, and counting rows would let a second narrator buy
 * the award.
 *
 * Completion is deliberately NOT required — the owner's reading (#701) is
 * three BOOKS of one universe, not three finished ones. The series awards next
 * door are the ones about finishing.
 *
 * The curated lists arrive as data ([UniverseList], the `universes/` assets)
 * rather than being loaded here: the module reads a snapshot, and a test can
 * then pin a universe without inventing one in the shipped assets.
 */
object UniverseBreadth {

    fun largest(books: List<OwnLibraryBook>, universes: List<UniverseList>): Long =
        books.mapNotNull { book ->
            UniverseMatcher.resolve(universes, book.seriesTitle.orEmpty(), book.seriesUrl)
                ?.let { match -> match.universe.id to book.workId }
        }
            .distinct()
            .groupingBy { it.first }
            .eachCount()
            .values
            .maxOrNull()
            ?.toLong()
            ?: 0L
}
