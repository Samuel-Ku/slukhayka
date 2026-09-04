package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.EditionId
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
    val url: String,
    /** Explicit rendition ownership; automatic fallback never crosses it. */
    val editionId: String = "",
    /**
     * Spec-45 (#405) R5 (#512): the member's effective content language
     * (BCP-47, own claim else the source's declared catalogue language; ""
     * = unknown). Kept PER SOURCE so the shared search cache holds enough
     * data to re-filter a mixed card under any selection.
     */
    val language: String = ""
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
    val sources: List<GlobalSearchSource>,
    /**
     * Spec-45 (#405) T5 (#493): the Work's known content language (BCP-47) —
     * derived at merge time from the member books' effective languages; ""
     * when unknown or when the sources disagree (mixed cards stay visible
     * under any single-language selection, mirroring the WorkFeed rule that
     * a Work with a matching Edition shows). Rendered nowhere today — it
     * feeds the content-language filter (T5) and the EN/UA badges (T7).
     */
    val language: String = ""
) {
    /** Stable identity for list keys: the merge key, else the first source url. */
    val key: String get() = mergeKey.ifBlank { sources.firstOrNull()?.url ?: title }
}

/**
 * Spec-45 (#405) T5 (#493): whether a card of [language] is visible under a
 * content-language [selection] (US6/US21). An EMPTY selection = «Усі» (both
 * content languages on) = inactive — everything shows. A card whose language
 * is unknown ("") is never hidden by any selection (US17). The SAME rule the
 * persisted WorkFeed DAO applies per Edition row (T4/R4) and the merged-card
 * filter applies per Source member (R5) — one semantics across every surface.
 */
fun contentLanguageVisible(language: String, selection: Set<String>): Boolean =
    selection.isEmpty() || language.isBlank() || language in selection

/**
 * Spec-45 (#405) R5 (#512) — [contentLanguageVisible] applied at the
 * SOURCE-member level of a merged card, the shape the shared search cache
 * stores and every published surface re-reads. A mixed uk/en card under a
 * uk selection keeps only its uk source; en+unknown keeps the unknown
 * member; a card whose every member is hidden disappears. The card language
 * is re-derived from the SURVIVING members (uk+en under uk → "uk";
 * en+unknown → "" — never "en", so the unknown member never hides). An
 * empty selection stays inactive («Усі»).
 */
fun List<GlobalSearchResult>.visibleInContentLanguages(selection: Set<String>): List<GlobalSearchResult> =
    mapNotNull { result ->
        val kept = result.sources.filter { contentLanguageVisible(it.language, selection) }
        if (kept.isEmpty()) {
            null
        } else {
            result.copy(
                sources = kept,
                language = kept.mapNotNull { it.language.ifBlank { null } }
                    .distinct().singleOrNull().orEmpty()
            )
        }
    }

/**
 * Spec-15 T4/#426 — whether a catalogue card may offer one-tap download: any
 * source in the capability order may satisfy it, except a stream-only source.
 * This lets a Lihtar-first Work still download from its attached 4read or
 * Sound-Books edition.
 */
fun catalogCardDownloadAllowed(result: GlobalSearchResult): Boolean =
    result.sources.any { !streamOnlyFor(it.sourceId) }

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
    // Spec-45 (#405) T2 (#490): archive.org/details is the LibriVox MIRROR
    // transport — the same sourceId, never a second catalogue row. Only
    // librivoxaudio items reach the library through this adapter, so the URL
    // shape maps to librivox, not to a generic archive source.
    url.contains("archive.org/details/") -> "librivox"
    else -> "unknown"
}

/**
 * Spec-42 #440 — the 4read search URL for a free-text [query], URL-encoded the
 * same way [FourReadAdapter.search] does. Pure JVM so the door's target can be
 * pinned without a WebView. 4read resolves to the in-app browser in every build
 * (ADR-0027), so this is the release-accessible pre-filled search.
 */
fun fourReadSearchUrl(query: String): String {
    val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
    return "https://4read.org/index.php?do=search&subaction=search&story=$encoded"
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
    "librivox" -> "LibriVox"
    "local" -> "Локальна"
    else -> sourceId
}

/**
 * Merges raw per-source matches into one card per Work. Deterministic: cards
 * are ordered by title (case-insensitive), sources within a card by the shared
 * capability policy and stable name/id/url ties.
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
                // One Work language: known only when every member source
                // agrees on the same claim; "" (unknown) when any member is
                // unclaimed or the sources disagree — unknown never hides.
                language = books.mapNotNull { it.language.ifBlank { null } }.distinct().singleOrNull().orEmpty(),
                // One badge per source, whatever urls it returned. The first
                // source is the default playback/download choice and follows
                // the shared capability policy (direct before browser).
                sources = SourceAccessPolicy.order(
                    books
                        .map { SourceAccessCandidate(it.sourceId, sourceDisplayName(it.sourceId), it.url) }
                        .distinctBy { it.sourceId }
                ).map { candidate ->
                    val book = books.first { it.sourceId == candidate.sourceId && it.url == candidate.url }
                    val narrator = MetadataAssertions.normalizeClaimedText(book.narrator)
                        ?: "${book.sourceId} narrator"
                    GlobalSearchSource(
                        sourceId = candidate.sourceId,
                        sourceName = candidate.sourceName,
                        url = candidate.url,
                        editionId = EditionId.forBook(MergeKey.keyFor(book.title, book.author), "", narrator),
                        // R5 (#512): the member's effective language rides the
                        // source so cached cards keep filtering correctly.
                        language = book.language
                    )
                }
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}
