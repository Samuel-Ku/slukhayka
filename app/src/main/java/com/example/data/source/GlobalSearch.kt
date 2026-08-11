package com.example.data.source

import com.example.data.merge.MergeKey

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
    val sources: List<GlobalSearchSource>
) {
    /** Stable identity for list keys: the merge key, else the first source url. */
    val key: String get() = mergeKey.ifBlank { sources.firstOrNull()?.url ?: title }
}

/** Human-readable source label for badges. */
fun sourceDisplayName(sourceId: String): String = when (sourceId) {
    "4read" -> "4read"
    "soundbooks" -> "Sound-Books"
    "audiobookmp3" -> "audiobook-mp3"
    "lihtar" -> "Lihtar"
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
        MergeKey.keyFor(book.title, book.author, book.narrator)
            .ifBlank { "${book.sourceId}|${book.url}" }
    }
    return grouped.values
        .map { books ->
            val first = books.first()
            GlobalSearchResult(
                title = first.title,
                author = first.author,
                narrator = first.narrator,
                mergeKey = MergeKey.keyFor(first.title, first.author, first.narrator),
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
