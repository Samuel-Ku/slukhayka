package com.example.data.merge

import java.util.Locale

/**
 * Spec-10 T2 — the Work-level dedup key of the multi-source catalog.
 *
 * A book imported from two different sources is the same Work when its
 * normalized (title + author + narrator) key matches. The narrator is part of
 * the key only when known, so two genuinely different narrations of the same
 * text stay separate cards, while books with no narrator on either side merge
 * on title + author alone (ADR-0001: incompatible narrations must not share
 * timestamps; a blank narrator is "unknown", never "different").
 *
 * The normalization reuses the approach validated in the enrichment spike
 * (wayfinder #43 / Google Books + OpenLibrary matcher): lowercase, strip
 * punctuation, collapse whitespace, and cut subtitles after ':' / '—' / '–'.
 * Pure JVM so the merge rule is unit-testable without Android.
 */
object MergeKey {

    /** Normalizes a title for comparison: lowercases, strips a subtitle. */
    fun normalizeTitle(title: String): String {
        val withoutSubtitle = title
            .substringBefore(':')
            .substringBefore('—')
            .substringBefore('–')
        return normalize(withoutSubtitle)
    }

    /** Normalizes an author or narrator name for comparison. */
    fun normalizePerson(name: String): String = normalize(name)

    /**
     * The merge key of a book: `normalizedTitle|normalizedAuthor[|normalizedNarrator]`.
     * Blank when nothing usable is present (never merged).
     */
    fun keyFor(title: String, author: String, narrator: String): String {
        val base = listOf(normalizeTitle(title), normalizePerson(author))
            .filter { it.isNotBlank() }
        if (base.size < 2) return ""
        val parts = if (narrator.isNotBlank()) base + normalizePerson(narrator) else base
        return parts.joinToString("|")
    }

    private fun normalize(input: String): String {
        val cleaned = input
            .trim()
            .lowercase(Locale.ROOT)
            // Keep letters, digits and spaces; drop everything else (incl. ' і ' variants).
            .replace(Regex("[^\\p{L}\\p{N} ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned
    }
}
