package com.example.data.merge

import java.util.Locale

/**
 * Spec-10 T2 + ADR-0010 — the Work-level dedup key of the multi-source
 * catalog.
 *
 * A book imported from two different sources is the same Work when its
 * normalized (title + author) key matches. The narrator is NOT part of the
 * Work identity: it distinguishes EDITIONS (renditions) of the same Work, so
 * two different narrations of the same text are ONE Work with TWO Editions,
 * never two Works (ADR-0010). The Edition id carries the narrator
 * ([EditionId.forBook]), so incompatible narrations still never share
 * listening state (ADR-0001) even though they share the Work.
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
     * The merge key of a book: `normalizedTitle|normalizedAuthor`. The
     * narrator is deliberately NOT here — it is an Edition property
     * (ADR-0010), so every narration of the same text shares this Work key.
     * Blank when nothing usable is present (never merged).
     */
    fun keyFor(title: String, author: String): String {
        val base = listOf(normalizeTitle(title), normalizePerson(author))
            .filter { it.isNotBlank() }
        if (base.size < 2) return ""
        return base.joinToString("|")
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
