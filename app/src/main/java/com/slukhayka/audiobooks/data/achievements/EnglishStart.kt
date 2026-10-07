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
 * The unit is the BOOK, not the Edition: a book whose two renditions both
 * claim English is still one English start.
 */
object EnglishStart {
    fun count(claims: List<BookLanguageClaim>): Long =
        claims.filter { LanguageCode.normalize(it.language) == LanguageCode.ENGLISH }
            .map { it.bookId }
            .distinct()
            .size
            .toLong()
}
