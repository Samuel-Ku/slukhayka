package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.search.SearchCache
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.SourceAccessCandidate
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.mergeGlobalSearchResults
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Spec-49 T2a — the Replacement Mapping resolver (ADR-0037 §3): the pure JVM
 * generalization of the #469 cross-resolve from sluhay-only to ALL direct
 * sources. A Work whose audio is refused ([com.slukhayka.audiobooks.data.source.SourceAudioRefusal]
 * — its Sources are never offered) or absent maps, when the listener touches
 * the book, onto a direct counterpart.
 *
 * The discipline is the Cross-resolve one, widened:
 *
 * - **Step 1 — zero requests.** The local union, then the shared [SearchCache]
 *   — the common case costs no network at all.
 * - **Step 2 — ONE parallel volley.** On a miss, one `title author` search
 *   across every supplied direct source fires concurrently (never a
 *   sequential crawl, never one volley per candidate step), the raw matches
 *   merge into Work cards by [MergeKey], and the merged cards ride back into
 *   the shared base best-effort so the next listener skips the volley.
 * - **MergeKey agreement is the whole match rule.** A near miss is a
 *   negative verdict, never «something similar»; browser members of a
 *   matching card are skipped — replacing a refused source with another
 *   browser door would rebuild the door the mapping exists to leave behind.
 * - **Verdict memo per Work** — the Edition Availability Assertion discipline
 *   ([CatalogAvailabilityPolicy.isFresh]): a positive verdict is fresh for
 *   6 hours, a negative one for 15 minutes; both are stale at the exact
 *   expiry boundary, so repeated taps never re-request inside the window.
 * - **Best-effort and silent by contract.** A failing source, a failing
 *   union read, a failing store or a corrupt document all degrade to
 *   «no match» — no exception ever escapes.
 *
 * Pure JVM, no Android: sources arrive behind the adapter seam as
 * `search(query)` functions keyed by source id — the wiring (which adapters
 * are the direct sources of today) belongs to the composition task (T2b),
 * not here. No background or batch mapping exists: the resolver runs only
 * inside a listener-initiated touch.
 */
class SourceReplacementMapping(
    /**
     * The direct-source search seam — one entry per direct source, keyed by
     * its stable sourceId, each invocation equaling one HTTP request. Every
     * supplied entry joins the single volley; nothing here decides WHICH
     * sources are direct (the composition does).
     */
    private val directSearches: Map<String, suspend (query: String) -> List<SourceBook>>,
    /** The local union read — zero requests, whatever the catalog already holds. */
    private val union: suspend () -> List<GlobalSearchResult>,
    private val cache: SearchCache? = null,
    /**
     * The local sitemap Work index seam (ADR-0042): answers from book URLs
     * already enumerated, with zero requests. Consulted between the shared
     * cache and the live volley; a null/absent index changes nothing.
     */
    private val workIndex: (suspend (title: String, author: String, mergeKey: String) -> Match?)? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * The mapped direct counterpart: everything the import door needs. The
     * narrator is the found source's own claim (absent stays absent, never
     * guessed — ADR-0014); the Edition attachment (same Edition on narrator
     * agreement, Narration Claim otherwise) is the import task's decision,
     * not the resolver's.
     */
    data class Match(
        val sourceId: String,
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
     * The direct counterpart of the Work, or null. Fires no network request
     * while a fresh memo, the union, the shared cache or the local Work index
     * answers — and at most ONE parallel volley when they all miss.
     *
     * [force] bypasses the verdict memo for a listener-initiated re-check
     * (spec-56 T2): the tap asks for current truth, not the cached verdict.
     */
    suspend fun resolve(title: String, author: String, mergeKey: String, force: Boolean = false): Match? {
        if (mergeKey.isBlank()) return null
        val now = clock()
        if (!force) {
            verdicts[mergeKey]?.let { verdict ->
                if (CatalogAvailabilityPolicy.isFresh(verdict.matched, verdict.observedAtMillis, now)) {
                    return verdict.match
                }
            }
        }
        verdicts.remove(mergeKey)

        val query = listOf(title.trim(), author.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (query.isEmpty()) return null

        val match = resolveFromUnion(mergeKey)
            ?: resolveFromSharedCache(query, mergeKey)
            ?: runCatching { workIndex?.invoke(title, author, mergeKey) }.getOrNull()
            ?: resolveVolley(query, mergeKey)
        verdicts[mergeKey] = Verdict(match != null, now, match)
        return match
    }

    /** The union the catalog already holds — a pure local read, zero requests. */
    private suspend fun resolveFromUnion(mergeKey: String): Match? =
        matchIn(runCatching { union() }.getOrDefault(emptyList()), mergeKey)

    /**
     * The zero-request half of [resolve]: union → shared cache → local Work
     * index, never the live volley. The background availability queue uses
     * this so a background scan spends no source tokens at all; the live
     * volley stays listener-initiated (spec-56 T3).
     */
    suspend fun resolveLocalOnly(title: String, author: String, mergeKey: String): Match? {
        if (mergeKey.isBlank()) return null
        val query = listOf(title.trim(), author.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (query.isEmpty()) return null
        return resolveFromUnion(mergeKey)
            ?: resolveFromSharedCache(query, mergeKey)
            ?: runCatching { workIndex?.invoke(title, author, mergeKey) }.getOrNull()
    }

    /** Fresh shared-base entry serves the touch without a volley. */
    private suspend fun resolveFromSharedCache(query: String, mergeKey: String): Match? {
        val results = runCatching { cache?.getResults(query) }.getOrNull() ?: return null
        return matchIn(results, mergeKey)
    }

    /**
     * The ONE live volley on a miss: every direct source searched
     * concurrently, the raw matches merged into Work cards by the shared
     * MergeKey rule, the merged cards written back to the shared base
     * best-effort (the no-negative rule inside [SearchCache] holds — a miss
     * is never cached there). A failing source contributes nothing and never
     * breaks the sweep.
     */
    private suspend fun resolveVolley(query: String, mergeKey: String): Match? {
        val results = coroutineScope {
            directSearches.map { (sourceId, search) ->
                async {
                    // A failing source contributes nothing; a CANCELLED volley
                    // is not a failing source — cancellation is rethrown so
                    // the sweep stays cooperatively cancellable.
                    val books = runCatching { search(query) }
                        .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptyList() }
                    sourceId to books
                }
            }.awaitAll()
        }
        val merged = mergeGlobalSearchResults(results.flatMap { (_, books) -> books })
        runCatching { cache?.putResults(query, merged) }
        return matchIn(merged, mergeKey)
    }

    /**
     * MergeKey agreement is the whole match rule; the card's DIRECT members
     * compete by the shared capability order. A matching card without a
     * direct member (browser-only) is skipped, not turned into a match.
     */
    private fun matchIn(results: List<GlobalSearchResult>, mergeKey: String): Match? {
        for (result in results) {
            val matched = result.mergeKey == mergeKey ||
                MergeKey.keyFor(result.title, result.author) == mergeKey
            if (!matched) continue
            val direct = result.sources
                .filter { it.url.isNotBlank() && SourceAccessPolicy.modeFor(it.sourceId) == SourceAccessMode.DIRECT }
                .map { SourceAccessCandidate(it.sourceId, it.sourceName, it.url) }
            val chosen = SourceAccessPolicy.order(direct).firstOrNull() ?: continue
            return Match(
                sourceId = chosen.sourceId,
                url = chosen.url,
                title = result.title,
                author = result.author,
                narrator = result.narrator,
                coverImageUrl = result.coverImageUrl
            )
        }
        return null
    }
}
