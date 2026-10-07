package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.LanguageCode

/** #701 — one recorded start together with the language its rendition claims. */
data class BookLanguageClaim(val bookId: String, val language: String)

/**
 * #701 — «Англомовний старт»: books the listener STARTED that really have an
 * English rendition.
 *
 * Two facts must both be true: a recorded `RESUME` for the book, and a
 * rendition whose language resolves to `en`. The claim goes through the ONE
 * vocabulary ([LanguageCode.normalize]) — the same rule every adapter uses —
 * so a raw label a source reports as text (`English`, `en-US`, `eng`) still
 * lands on the canonical BCP-47 code, while a claim nobody can map (`Klingon`,
 * blank) stays UNKNOWN and counts for nothing (ADR-0014).
 *
 * The unit is the BOOK, not the Edition: `playback_events` carries no
 * `editionId`, so a start cannot be attributed to one rendition — a book with
 * both a Ukrainian and an English rendition counts once, and that is the
 * published decision rather than an inference from this query's shape.
 *
 * Only the rendition's OWN claim is read (`editions.language`), never the
 * shared `edition_facets`: a language known merely from a common facet does not
 * open the award.
 */
object EnglishStart {
    fun count(claims: List<BookLanguageClaim>): Long =
        claims.filter { LanguageCode.normalize(it.language) == LanguageCode.ENGLISH }
            .map { it.bookId }
            .distinct()
            .size
            .toLong()
}
