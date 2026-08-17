package com.slukhayka.audiobooks.data.search

import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import java.util.Locale

/**
 * Spec-33 T1 (#229) — the shared search-result cache behind a pure JVM seam:
 * a merged search result (covers, narrators, durations included — spec-33
 * US-4) keyed by the normalized query, served to every listener instead of
 * hammering the 4read / sluhayua search endpoints again.
 *
 * The policy lives IN the seam as default methods — key normalization,
 * the ~24-hour freshness window and the no-negative-cache rule — so the
 * seam is pure JVM and fixture-testable over a fake document store (spec-33
 * Testing Decisions; prior art: the universe store fixture tests). The
 * transport ([readDocument] / [writeDocument]) is the only part an
 * implementation supplies — [FirestoreSearchCache] is thin glue, exactly
 * like the shared-book-metadata seam.
 *
 * Degrade-never by contract: a miss, a stale entry, a failing or corrupt
 * store all yield null / a silent no-op — the caller falls through to the
 * live sources exactly as before.
 */
interface SearchCache {

    /**
     * The merged result for [query], or null on a miss, a stale entry, a
     * corrupt document or a failing store — never an exception.
     */
    suspend fun getResults(query: String): List<GlobalSearchResult>? {
        val key = SearchQueryKey.normalize(query) ?: return null
        val entry = runCatching { readDocument(key) }
            .getOrNull()?.let { SearchResultCodec.fromMap(it) } ?: return null
        return if (SearchFreshness.isFresh(entry.fetchedAt, nowMillis())) entry.results else null
    }

    /**
     * Best-effort write-back of a merged result, keyed by the SAME normalized
     * query the read path uses, so the next listener reads it instead of
     * re-resolving. Empty result lists are never cached (spec-33 US-8 — the
     * long tail of unique miss queries stays unbounded-free); a failing write
     * contributes nothing.
     */
    suspend fun putResults(query: String, results: List<GlobalSearchResult>) {
        if (results.isEmpty()) return
        val key = SearchQueryKey.normalize(query) ?: return
        runCatching { writeDocument(key, SearchResultCodec.toMap(nowMillis(), results)) }
    }

    /** The raw document for a normalized query key, or null on miss/failure. */
    suspend fun readDocument(queryKey: String): Map<String, Any>?

    /** One raw document write, best-effort — the transport may not throw. */
    suspend fun writeDocument(queryKey: String, document: Map<String, Any>)

    /** The clock the freshness window is measured against; overridable in tests. */
    fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * Spec-33 — the normalized cache key of a query: trimmed, case-folded and
 * whitespace-collapsed (US-7), so casing and extra spaces never split one
 * query into many cache entries. Blank input normalizes to null — such a
 * query is never a cache question.
 */
object SearchQueryKey {

    private val WHITESPACE = Regex("\\s+")

    fun normalize(query: String): String? {
        // Locale.ROOT fold: a shared cache key must be deterministic across
        // devices (a Turkish-locale device would otherwise fold "I" → "ı" and
        // split one query into two keys — the same reason MergeKey folds ROOT).
        val key = query.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)
        return key.ifBlank { null }
    }
}

/**
 * Spec-33 — the freshness window of a cached search result: ~24 hours, so a
 * newly added book appears within a day (US-5) while popular queries still
 * get served instantly (US-1/US-2). An entry at or beyond the bound is a
 * miss and the query re-resolves from the sources.
 */
object SearchFreshness {

    /** ~24 hours — the short TTL keeps the cache from hiding new releases. */
    const val FRESHNESS_MILLIS = 24L * 60 * 60 * 1000

    fun isFresh(fetchedAt: Long, nowMillis: Long): Boolean =
        nowMillis - fetchedAt < FRESHNESS_MILLIS
}

/** One decoded cache document: when it was fetched and the merged result. */
data class SearchCacheEntry(
    val fetchedAt: Long,
    val results: List<GlobalSearchResult>
)

/**
 * Spec-33 — the Firestore document codec for a merged search result — pure
 * JVM so the shape is unit-testable without Firebase. Document fields:
 *
 * ```
 * fetchedAt: Long                   (provenance — when the result was fetched)
 * results:   [ { title, author, narrator, mergeKey, coverImageUrl?,
 *                durationSeconds?, sources: [ {sourceId, sourceName, url} ] } ]
 * ```
 *
 * [toMap] bounds the write to [MAX_RESULTS] cards (spec-33 — "a result is
 * bounded to a sane number of cards"); [fromMap] is defensive: any
 * missing/mistyped required field, an empty or oversized result list, a card
 * without a title, or a card without at least one source URL yields null (a
 * corrupt document is a miss, never a crash).
 */
object SearchResultCodec {

    /** The card cap — a sane, bounded result (spec-33 write limits). */
    const val MAX_RESULTS = 100

    fun toMap(fetchedAt: Long, results: List<GlobalSearchResult>): Map<String, Any> = mapOf(
        "fetchedAt" to fetchedAt,
        "results" to results.take(MAX_RESULTS).map { resultToMap(it) }
    )

    fun fromMap(map: Map<String, Any>): SearchCacheEntry? {
        val fetchedAt = (map["fetchedAt"] as? Number)?.toLong() ?: return null
        val rawResults = map["results"] as? List<*> ?: return null
        // Negatives are never cached (the write path skips empty lists), so
        // an empty or oversized cached list is corrupt.
        if (rawResults.isEmpty() || rawResults.size > MAX_RESULTS) return null
        val results = mutableListOf<GlobalSearchResult>()
        for (raw in rawResults) {
            results += resultFromMap(raw as? Map<*, *> ?: return null) ?: return null
        }
        return SearchCacheEntry(fetchedAt, results)
    }

    private fun resultToMap(result: GlobalSearchResult): Map<String, Any> =
        mapOf(
            "title" to result.title,
            "author" to result.author,
            "narrator" to result.narrator,
            "mergeKey" to result.mergeKey,
            "sources" to result.sources.map { source ->
                mapOf(
                    "sourceId" to source.sourceId,
                    "sourceName" to source.sourceName,
                    "url" to source.url
                )
            }
        ) + optionalFields(result)

    /** Optional fields ride the document only when present; reads default null. */
    private fun optionalFields(result: GlobalSearchResult): Map<String, Any> = buildMap {
        result.coverImageUrl?.let { put("coverImageUrl", it) }
        result.durationSeconds?.let { put("durationSeconds", it) }
    }

    private fun resultFromMap(map: Map<*, *>): GlobalSearchResult? {
        val title = map["title"] as? String ?: return null
        if (title.isBlank()) return null
        val rawSources = map["sources"] as? List<*> ?: return null
        if (rawSources.isEmpty()) return null
        val sources = mutableListOf<GlobalSearchSource>()
        for (raw in rawSources) {
            val source = sourceFromMap(raw as? Map<*, *> ?: return null) ?: return null
            sources += source
        }
        return GlobalSearchResult(
            title = title,
            author = map["author"] as? String ?: "",
            narrator = map["narrator"] as? String ?: "",
            mergeKey = map["mergeKey"] as? String ?: "",
            coverImageUrl = map["coverImageUrl"] as? String,
            durationSeconds = (map["durationSeconds"] as? Number)?.toLong(),
            sources = sources
        )
    }

    private fun sourceFromMap(map: Map<*, *>): GlobalSearchSource? {
        val sourceId = map["sourceId"] as? String ?: return null
        val url = map["url"] as? String ?: return null
        if (url.isBlank()) return null
        return GlobalSearchSource(
            sourceId = sourceId,
            sourceName = map["sourceName"] as? String ?: "",
            url = url
        )
    }
}