package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions

/**
 * Spec-10 T4 — the global-search result model (pure JVM).
 *
 * A [GlobalSearchResult] is one search card: a Work with every source that
 * matched the query. Sources come from each adapter's `search()` (4read) or —
 * where the source has no usable search endpoint — from its recent feed
 * filtered by the query (spec-10 T1 verdicts: soundbooks/audiobookmp3/lihtar
 * return empty from `search`; discovery goes through `fetchNew`).
 *
 * The merge rule reuses the Work-level [MergeKey] (normalized
 * title|author|narrator) so the same book found on two sources collapses into
 * one card, mirroring `importBookFromSource` in the repository. Books without
 * a usable key (blank author) fall back to (sourceId, url) — they stay
 * separate cards, consistent with the T2 rule that a blank author never
 * merges.
 */

/** One playable source of a search-result card. */
data class GlobalSearchSource(
    val sourceId: String,
    val sourceName: String,
    val url: String
)

/** One search-result card: a Work with all matching sources. */
data class GlobalSearchResult(
    val title: String,
    val author: String,
    val narrator: String = "",
    val mergeKey: String,
    val coverImageUrl: String? = null,
    // Spec-30 T2 (#217): the card's duration when one is resolvable from the
    // local database or the shared metadata cache (client-first precedence),
    // null when unknown — the card then renders without a duration, never a
    // fabricated number.
    val durationSeconds: Long? = null,
    val sources: List<GlobalSearchSource>
) {
    /** Stable identity for list keys: the merge key, else the first source url. */
    val key: String get() = mergeKey.ifBlank { sources.firstOrNull()?.url ?: title }
}

/**
 * Spec-15 T4 — whether a catalogue card may offer one-tap download: its
 * primary (first) source is not stream-only. The card plays from that same
 * first source, so the download gate and the play source always agree (a
 * card whose first source is lihtar — ToS forbids reproduction — hides the
 * affordance, exactly as the detail screen does for the same book).
 */
fun catalogCardDownloadAllowed(result: GlobalSearchResult): Boolean =
    result.sources.firstOrNull()?.let { !streamOnlyFor(it.sourceId) } ?: false

/**
 * Spec-15 T6 — the stable source id of a book URL, as a pure function (the
 * repository's `sourceTypeOfUrl` delegates here; the library model uses it to
 * badge its cards without depending on the repository). Blank URL = a local
 * import. `sluhay.com.ua` is checked before `sluhay.com` (it contains it) and
 * `sluhayknigi.com` before `sluhay.com` (the CDN is shared, the Referer
 * differs).
 */
fun sourceIdForUrl(url: String): String = when {
    url.isBlank() -> "local"
    url.contains("4read.org") -> "4read"
    url.contains("sound-books.net") -> "soundbooks"
    url.contains("audiobook-mp3.com") -> "audiobookmp3"
    url.contains("lihtar.in.ua") -> "lihtar"
    url.contains("sluhay.com.ua") -> "sluhayua"
    url.contains("sluhayknigi.com") -> "sluhayknigi"
    url.contains("sluhay.com") -> "sluhay"
    else -> "unknown"
}

/** Human-readable source label for badges. */
fun sourceDisplayName(sourceId: String): String = when (sourceId) {
    "4read" -> "4read"
    "soundbooks" -> "Sound-Books"
    "audiobookmp3" -> "audiobook-mp3"
    "lihtar" -> "Lihtar"
    "sluhayua" -> "Sluhay"
    "sluhay" -> "Sluhay"
    "sluhayknigi" -> "SluhayKnigi"
    "local" -> "Локальна"
    else -> sourceId
}

/**
 * Merges raw per-source matches into one card per Work. Deterministic: cards
 * are ordered by title (case-insensitive), sources within a card by sourceId.
 * Junk rows (blank title or url) are dropped.
 */
fun mergeGlobalSearchResults(results: List<SourceBook>): List<GlobalSearchResult> {
    val usable = results.filter { it.title.isNotBlank() && it.url.isNotBlank() }
    val grouped = usable.groupBy { book ->
        // ADR-0010: cards merge on the bibliographic Work key — the narrator
        // distinguishes Editions of one card, not separate cards.
        MergeKey.keyFor(book.title, book.author)
            .ifBlank { "${book.sourceId}|${book.url}" }
    }
    return grouped.values
        .map { books ->
            val first = books.first()
            GlobalSearchResult(
                title = MetadataAssertions.normalizeTitle(first.title),
                author = first.author,
                narrator = first.narrator,
                mergeKey = MergeKey.keyFor(first.title, first.author),
                coverImageUrl = first.coverImageUrl,
                // One badge per source, whatever urls it returned; the first
                // url is the one the card plays from.
                sources = books
                    .map { GlobalSearchSource(it.sourceId, sourceDisplayName(it.sourceId), it.url) }
                    .distinctBy { it.sourceId }
                    .sortedBy { it.sourceId }
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}
