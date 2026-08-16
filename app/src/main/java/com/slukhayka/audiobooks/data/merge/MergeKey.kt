package com.slukhayka.audiobooks.data.merge

import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
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
 *
 * Spec-27 (#184) BUG-002 — the SAME curated SEO scrub the write paths apply
 * runs BEFORE the key is computed, and a trailing ` - ` segment (a site /
 * brand / author suffix — «Трохи ненависті - АудіоКниги Українською») is
 * cut like the ':' / '—' subtitles. The raw page title and the clean title
 * therefore produce the SAME key and merge into one Work instead of spawning
 * a second library card. One normalization seam, shared with the write
 * paths (ADR-0004) — the historical migrations and every future write agree.
 * Pure JVM so the merge rule is unit-testable without Android.
 */
object MergeKey {

    /** Normalizes a title for comparison: lowercases, strips a subtitle. */
    fun normalizeTitle(title: String): String {
        // Spec-27 BUG-002: the SEO scrub first — a raw page title with a
        // curated suffix («Трохи ненависті - АудіоКниги Українською») is
        // already clean when the subtitle cuts below run.
        val scrubbed = MetadataAssertions.normalizeTitle(title)
        val withoutSubtitle = scrubbed
            .substringBefore(':')
            .substringBefore('—')
            .substringBefore('–')
            .substringBefore(" - ")
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
