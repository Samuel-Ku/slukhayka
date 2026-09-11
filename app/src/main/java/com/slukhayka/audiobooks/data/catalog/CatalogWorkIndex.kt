package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.merge.SlugMatch

/**
 * One indexed book URL of a direct source.
 *
 * [mergeKey] is set when the entry came from a catalogue card (a real
 * title/author pair, matched exactly); sitemap entries carry only a
 * transliterated [slug] and are matched through [SlugMatch].
 */
data class CatalogIndexEntry(
    val sourceId: String,
    val url: String,
    val slug: String,
    val mergeKey: String = ""
)

/**
 * Spec-49 follow-up (2026-09-10) — the local Work index: direct-source book
 * URLs from sitemaps AND from catalogue-card enumeration, matched against a
 * Work's Cyrillic title/author. Zero network requests on a hit; a wrong
 * candidate can only cost the import door one page fetch, which then fails
 * honestly.
 *
 * Lookup order: an exact [CatalogIndexEntry.mergeKey] match (cards carry the
 * real identity) wins over the fuzzy slug match — the slug rule stays for
 * sources whose catalogue is sitemap-only.
 *
 * Pure JVM and immutable: the refresher builds a new instance after each
 * refresh and swaps it in, so a tap never sees a half-built index.
 */
class CatalogWorkIndex(private val entries: List<CatalogIndexEntry>) {

    /** The best indexed counterpart of the Work, or null. */
    fun lookup(title: String, author: String): CatalogIndexEntry? {
        val key = MergeKey.keyFor(title, author)
        if (key.isNotBlank()) {
            entries.firstOrNull { it.mergeKey.isNotBlank() && it.mergeKey == key }?.let { return it }
        }
        return entries.firstOrNull { it.mergeKey.isBlank() && SlugMatch.slugMatches(it.slug, title, author) }
    }

    val size: Int get() = entries.size
}
