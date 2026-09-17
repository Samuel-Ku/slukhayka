package com.slukhayka.audiobooks.data.search

import com.slukhayka.audiobooks.data.collections.CollectionMatcher

/**
 * Normalization for the local search index (#822, #823).
 *
 * Both sides of the index use the SAME fold — the [CollectionMatcher] fold
 * (MergeKey rule + trailing-parenthetical trim, Cyrillic-preserving
 * diacritics): the write side folds every field before the FTS insert, the
 * read side folds the raw query before building the MATCH expression. One
 * fold, never two, so «Шевченко (Кобзар)» and «шевченко» meet in the index.
 *
 * Pure JVM: no Android, no Room — unit-tested without Robolectric.
 */
object SearchIndexNormalize {

    /** Fold of a title-like field (Work title, series title). */
    fun titleField(text: String?): String =
        if (text.isNullOrBlank()) "" else CollectionMatcher.normalizeTitle(text)

    /** Fold of a person-like field (author, narrator). */
    fun personField(text: String?): String =
        if (text.isNullOrBlank()) "" else CollectionMatcher.normalizeAuthor(text)

    /**
     * Builds the FTS4 MATCH expression for a raw listener query: every token
     * becomes a BARE prefix term (`tok*`), so partial input matches from the
     * first keystrokes. Bare, never quoted: a quoted `"tok"*` does NOT
     * prefix-match (verified against SQLite — the quoted star matches
     * nothing, the bare star matches).
     *
     * The fold keeps only letters, digits and spaces, so a bare token can
     * never break out of the MATCH string — except the four FTS operator
     * words (AND/OR/NOT/NEAR), which stay quoted-exact without the star.
     * Returns null when nothing searchable remains (the caller reads no
     * index then).
     */
    fun matchQuery(query: String, maxTokens: Int = 10): String? {
        val tokens = CollectionMatcher.normalizeTitle(query)
            .split(' ')
            .filter { it.isNotBlank() }
            .take(maxTokens.coerceAtLeast(1))
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { token ->
            if (token.uppercase() in FTS_OPERATORS) "\"$token\"" else "$token*"
        }
    }

    private val FTS_OPERATORS = setOf("AND", "OR", "NOT", "NEAR")

    /**
     * Rank of one folded (title, author) pair against the folded query: an
     * exact title is the rendition the listener named (0), a title/author
     * prefix is the likely completion (1), anything else keeps its MATCH
     * order (2). The sort using this rank MUST be stable — rank ties keep
     * the index insertion order.
     */
    fun rankMatch(foldedTitle: String, foldedAuthor: String, foldedQuery: String): Int {
        if (foldedTitle.isBlank() || foldedQuery.isBlank()) return 2
        if (foldedTitle == foldedQuery) return 0
        if (foldedTitle.startsWith(foldedQuery) || foldedAuthor.startsWith(foldedQuery)) return 1
        return 2
    }
}
