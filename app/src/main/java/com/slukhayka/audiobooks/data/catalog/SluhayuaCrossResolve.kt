package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.search.SearchCache
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.mergeGlobalSearchResults
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Spec #462 ID7 (#469) — the tap-time cross-resolve of a 4read-only catalog
 * card onto the direct sluhayua source.
 *
 * When a card tap is about to end at the honest browser door (ADR-0026), the
 * coordinator asks this resolver for a direct counterpart of the same Work:
 * ONE JSON search request to sluhayua (`title author`), matched by the Work
 * [MergeKey]. A match lets the coordinator import that edition and play (or
 * open) without a browser; no match keeps the browser door as the only
 * answer. Never a background crawl — requests happen only inside a
 * listener-initiated tap.
 *
 * Caching mirrors the Edition Availability Assertion discipline
 * ([CatalogAvailabilityPolicy]): the per-Work verdict (match or no-match) is
 * memoized in memory — a positive verdict fresh for 6 hours, a negative one
 * for 15 minutes — so repeated taps never re-request. The merged search
 * cards are additionally written back to the shared metadata base through
 * the existing [SearchCache] seam (ADR-0028: the collective channel carries
 * metadata, never audio), and a fresh shared entry serves a tap without
 * touching sluhayua at all. Best-effort and silent by contract: a failing
 * search, a failing store or a corrupt document all degrade to «no match».
 */
class SluhayuaCrossResolve(
    /** The sluhayua search call — one invocation equals one HTTP request. */
    private val search: suspend (query: String) -> List<SourceBook>,
    private val cache: SearchCache? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** The matched direct sluhayua card: everything the import needs. */
    data class Match(
        val url: String,
        val title: String,
        val author: String,
        val narrator: String,
        val coverImageUrl: String?
    )

    private data class Verdict(
        val matched: Boolean,
        val observedAtMillis: Long,
        val match: Match?
    )

    /** Per-Work memo keyed by the Work mergeKey (positive 6h / negative 15m). */
    private val verdicts = ConcurrentHashMap<String, Verdict>()

    /**
     * The direct sluhayua match for the Work, or null. Issues at most one
     * live search request per call — and none at all while a fresh memo or
     * shared-cache verdict exists.
     */
    suspend fun resolve(title: String, author: String, mergeKey: String): Match? {
        if (mergeKey.isBlank()) return null
        val now = clock()
        verdicts[mergeKey]?.let { verdict ->
            if (CatalogAvailabilityPolicy.isFresh(verdict.matched, verdict.observedAtMillis, now)) {
                return verdict.match
            }
            verdicts.remove(mergeKey, verdict)
        }

        val query = listOf(title.trim(), author.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (query.isEmpty()) return null

        val match = resolveFromSharedCache(query, mergeKey) ?: resolveLive(query, mergeKey)
        verdicts[mergeKey] = Verdict(match != null, now, match)
        return match
    }

    /** Fresh shared-base entry serves the tap without a source request. */
    private suspend fun resolveFromSharedCache(query: String, mergeKey: String): Match? {
        val results = runCatching { cache?.getResults(query) }.getOrNull() ?: return null
        return matchIn(results, mergeKey)
    }

    /**
     * The ONE live search request per tap. The merged cards ride back into
     * the shared base best-effort (no-negative rule inside [SearchCache]),
     * so the next listener skips this request.
     */
    private suspend fun resolveLive(query: String, mergeKey: String): Match? {
        val results = runCatching { mergeGlobalSearchResults(search(query)) }
            .getOrDefault(emptyList())
        runCatching { cache?.putResults(query, results) }
        return matchIn(results, mergeKey)
    }

    /**
     * MergeKey agreement is the whole match rule. Only sluhayua sources
     * count: a shared-cache entry for the same query text may carry 4read
     * cards, and importing a browser source here would silently rebuild the
     * door the cross-resolve is trying to avoid.
     */
    private fun matchIn(results: List<GlobalSearchResult>, mergeKey: String): Match? {
        for (result in results) {
            val matched = result.mergeKey == mergeKey ||
                MergeKey.keyFor(result.title, result.author) == mergeKey
            if (!matched) continue
            val source = result.sources.firstOrNull {
                it.url.isNotBlank() && sourceIdForUrl(it.url) == "sluhayua"
            } ?: continue
            return Match(
                url = source.url,
                title = result.title,
                author = result.author,
                narrator = result.narrator,
                coverImageUrl = result.coverImageUrl
            )
        }
        return null
    }
}
