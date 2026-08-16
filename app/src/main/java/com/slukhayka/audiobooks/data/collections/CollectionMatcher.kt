package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import java.text.Normalizer

/**
 * Spec-16 T1 — the smart-collections matcher (pure JVM, no Android, no
 * network, no database).
 *
 * Takes the curated [CollectionList]s and the catalog union
 * ([GlobalSearchResult] cards — one card per Work) and returns, for each
 * collection, only the cards that are actually present. A match requires at
 * least author agreement:
 *
 *  - entry with a title → the book matches when BOTH the normalized author
 *    and the normalized title agree;
 *  - entry without a title (author-only fallback) → every catalog book of
 *    that author belongs to the collection;
 *  - a blank author never matches anything — a book never appears under an
 *    entry it does not match.
 *
 * An entry that matches nothing contributes nothing — the matcher never
 * fabricates a card (the enrichment-spike fallback: hide non-matches).
 * Several catalog cards sharing the entry's normalized title all match — the
 * union already collapses one Work into one card, so this is the same book in
 * practice.
 *
 * Normalization reuses the [MergeKey] rule the catalog union itself merges on
 * (case-fold, punctuation strip, whitespace collapse, subtitle cut at
 * ':' / '—' / '–') and adds two collection-specific folds on top:
 *
 *  - **Diacritics:** NFKD decomposition + combining-mark drop, so
 *    «García» ≈ «Garcia» (the mark is not a letter, so MergeKey alone would
 *    keep precomposed accents apart);
 *  - **Parenthetical annotation trimming:** a trailing «(повне видання)» /
 *    «(роман)» group is cut BEFORE the MergeKey rule (which would otherwise
 *    keep its words), so «Кобзар (повне видання)» ≈ «Кобзар».
 */
object CollectionMatcher {

    /** One collection with only its matched catalog cards. */
    data class MatchedCollection(
        val id: String,
        val name: String,
        val sourceNote: String,
        val books: List<GlobalSearchResult>
    )

    /** Normalizes a title: annotation trim + MergeKey title rule + diacritics. */
    fun normalizeTitle(title: String): String =
        MergeKey.normalizeTitle(stripTrailingParenthetical(title)).let { dropDiacritics(it) }

    /** Normalizes an author: annotation trim + MergeKey person rule + diacritics. */
    fun normalizeAuthor(author: String): String =
        MergeKey.normalizePerson(stripTrailingParenthetical(author)).let { dropDiacritics(it) }

    /** One entry matched against one catalog card. */
    fun entryMatches(entry: CollectionEntry, book: GlobalSearchResult): Boolean {
        if (entry.author.isBlank()) return false
        if (normalizeAuthor(book.author) != normalizeAuthor(entry.author)) return false
        val entryTitle = entry.title?.takeIf { it.isNotBlank() }
        if (entryTitle == null) return true // author-only fallback
        return normalizeTitle(book.title) == normalizeTitle(entryTitle)
    }

    /** Matches one collection against the catalog union. */
    fun match(collection: CollectionList, catalog: List<GlobalSearchResult>): MatchedCollection {
        val books = catalog.filter { book ->
            collection.entries.any { entryMatches(it, book) }
        }
        return MatchedCollection(collection.id, collection.name, collection.sourceNote, books)
    }

    /** Matches every collection and drops the empty ones (block hiding). */
    fun matchAll(
        collections: List<CollectionList>,
        catalog: List<GlobalSearchResult>
    ): List<MatchedCollection> =
        collections.map { match(it, catalog) }.filter { it.books.isNotEmpty() }

    private fun dropDiacritics(value: String): String {
        // NFKD only on NON-Cyrillic characters: Ukrainian ї/й/ё decompose
        // canonically (ї → і + combining diaeresis) and must NOT be folded —
        // «Енеїда» stays «Енеїда», «Гайдамаки» stays «Гайдамаки». Latin
        // accents (García → Garcia) and æ/œ/ß folds still apply.
        val sb = StringBuilder(value.length)
        for (ch in value) {
            if (ch in '\u0400'..'\u052F') {
                sb.append(ch) // Cyrillic blocks untouched
            } else {
                val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFKD)
                for (d in decomposed) {
                    if (d.code !in 0x0300..0x036F) sb.append(d) // drop combining marks
                }
            }
        }
        return sb.toString()
    }

    /** Cuts one trailing parenthetical annotation group, repeatedly
     *  («Кобзар (повне видання) (т. 1)» → «Кобзар»). */
    private fun stripTrailingParenthetical(value: String): String {
        var result = value
        while (true) {
            val trimmed = result.trimEnd()
            val open = trimmed.lastIndexOf('(')
            if (open <= 0 || !trimmed.endsWith(')')) return trimmed
            result = trimmed.substring(0, open).trimEnd()
        }
    }
}
