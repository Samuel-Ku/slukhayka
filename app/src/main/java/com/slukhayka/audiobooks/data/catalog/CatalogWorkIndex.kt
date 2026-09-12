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

    /**
     * #526 — the bounded candidate list for ONE explicit action: the exact
     * MergeKey matches first, then the slug matches, all of the SAME source as
     * the best match and never more than [limit] canonical URLs. Three is the
     * whole budget: the caller may open a candidate page only inside it, so a
     * failed candidate costs at most two more requests, never a crawl.
     */
    fun candidates(
        title: String,
        author: String,
        limit: Int = MAX_CANDIDATES
    ): List<CatalogIndexEntry> {
        if (limit <= 0) return emptyList()
        val key = MergeKey.keyFor(title, author)
        val exact = if (key.isBlank()) {
            emptyList()
        } else {
            entries.filter { it.mergeKey.isNotBlank() && it.mergeKey == key }
        }
        val best = exact.firstOrNull() ?: lookup(title, author) ?: return emptyList()
        // One source per action: its own candidates only.
        val pool = (exact.ifEmpty { entries.filter { SlugMatch.slugMatches(it.slug, title, author) } })
            .filter { it.sourceId == best.sourceId }
        return pool.distinctBy { it.url }.take(limit)
    }

    val size: Int get() = entries.size

    /** #526 — the entries of one source, for reuse when a sitemap answers 304. */
    fun entriesFor(sourceId: String): List<CatalogIndexEntry> =
        entries.filter { it.sourceId == sourceId }

    /** #526 — the whole index, for re-persisting after a 304-only sweep. */
    val allEntriesSnapshot: List<CatalogIndexEntry> get() = entries

    companion object {
        /** #526 — one action opens at most three candidate pages of one source. */
        const val MAX_CANDIDATES: Int = 3
    }
}
