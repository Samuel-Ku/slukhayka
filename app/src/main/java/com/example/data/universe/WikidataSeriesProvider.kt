package com.example.data.universe

import com.example.data.collections.CollectionMatcher
import com.example.data.merge.MergeKey
import java.net.URLEncoder
import kotlinx.coroutines.delay

/**
 * Spec-25 T2 (#173) — the Wikidata provider behind the [SeriesUniverseProvider]
 * seam: resolves a book the curated set does not know by searching the WORK
 * title on Wikidata, verifying the candidate's author (P50), following P179
 * to the series item, and building the series' precedes/follows chain from
 * P155/P156. The result is a curated-shaped [UniverseResolution] that
 * [SeriesUniverses] persists into the shared cache — so each book resolves
 * at most once.
 *
 * Spec-26 T1 (#175) adds the title-translation fallback: when the direct
 * uk → ru → en search is empty, the uk title is translated uk → ru/en via
 * the [TitleTranslator] seam (ML Kit, on-device) and the search retried.
 *
 * Spec-26 T2 (#176) adds the author to the search: the author name is
 * resolved to its Wikidata QID (wbsearchentities uk → ru → en, verified by
 * its label against the book's author — the same normalization on both
 * sides) and the work is searched by CirrusSearch `haswbstatement:P50=<qid>`
 * + normalized title tokens, so ambiguous titles narrow at search time
 * instead of relying on P50 candidate verification alone. The author-aware
 * pass is best-effort: an unresolvable author, an unverified QID or an
 * empty narrowed search falls through to the plain title pass.
 *
 * Spec-26 T3 (#177) adds the 429 retry: every fetch rides a retry loop with
 * exponential backoff + jitter ([WikidataRetryPolicy]) — a rate-limited
 * response is retried up to [maxAttempts] tries instead of silently losing
 * the resolution; any other status (or an exhausted limit) returns as-is,
 * and a 429 past the limit carries an empty body, so the caller degrades
 * silently and the next book open repeats.
 *
 * Spec-26 T4 (#179) adds the failure diagnostics: the optional [diagnostic]
 * callback is invoked once per resolve at the first failure point (and per
 * exhausted-429 request), so the residual-measurement harness classifies
 * catalog misses by cause — the surfaces themselves still degrade to
 * nothing.
 *
 * Best-effort and silent by design: no network, no search hits in any of
 * uk → ru → en (direct or translated), no author agreement, no P179, a
 * malformed response or an ambiguous candidate set all yield null — the
 * surfaces never degrade. The transport is injected (`fetch` returns a
 * [WikidataResponse] — status + body, the body "" on any failure, the
 * degrade-never-throw convention of the adapter seam); fixture tests serve
 * canned API responses by URL.
 */
class WikidataSeriesProvider(
    private val fetch: suspend (String) -> WikidataResponse,
    private val languages: List<String> = listOf("uk", "ru", "en"),
    private val maxCandidates: Int = 3,
    private val maxChainHops: Int = 8,
    private val translator: TitleTranslator? = null,
    private val maxAttempts: Int = 3,
    private val retryDelayMs: (Int) -> Long = { WikidataRetryPolicy.backoffDelayMs(it) },
    // Spec-26 T4: research/eval support — invoked once per resolve at the
    // first failure point (and per exhausted-429 request). Null in
    // production wiring; the residual harness collects it to classify
    // catalog misses by cause.
    private val diagnostic: ((ResolutionDiagnostic) -> Unit)? = null
) : SeriesUniverseProvider {

    override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
        if (bookTitle.isBlank() || bookAuthor.isBlank()) return null
        // 1. Search the work — author-aware first (spec-26 T2: the query
        //    carries the author as the P50 entity constraint + normalized
        //    title tokens, so ambiguous titles narrow at search time), then
        //    the plain title pass uk → ru → en, then the translated pass
        //    (spec-26 T1). First pass with hits wins — never a union, so
        //    ambiguity stays contained. A book whose only Wikidata labels
        //    are ru/en still resolves via the translated pass.
        val candidates = searchByAuthor(bookTitle, bookAuthor)
            ?: search(bookTitle)
            ?: searchTranslated(bookTitle)
            ?: run { diagnostic?.invoke(ResolutionDiagnostic.SEARCH_MISS); return null }
        // 2. Candidate verification: the first candidate whose P50 author
        //    agrees with the book's author wins; none agreeing → no resolution.
        val workQid = candidates.firstOrNull { authorMatches(it, bookAuthor) }
            ?: run { diagnostic?.invoke(ResolutionDiagnostic.AUTHOR_MISMATCH); return null }
        // 3. P179 → the series item.
        val workJson = fetchEntity(workQid) ?: return null
        val seriesQid = WikidataParser.seriesIds(workJson, workQid).firstOrNull()
            ?: run { diagnostic?.invoke(ResolutionDiagnostic.NO_SERIES_CLAIM); return null }
        // 4. The series' P155/P156 chain, bounded in both directions; the
        //    chain head names the universe and anchors its id.
        val chain = buildChain(seriesQid)
            ?: run { diagnostic?.invoke(ResolutionDiagnostic.CHAIN_UNPLACEABLE); return null }
        val position = chain.indexOfFirst { it.qid == seriesQid }
        if (position < 0) {
            diagnostic?.invoke(ResolutionDiagnostic.CHAIN_UNPLACEABLE)
            return null
        }
        val head = chain.first()
        val universeName = head.label ?: return null
        return UniverseResolution(
            universe = UniverseList(
                id = "wd:${head.qid}",
                name = universeName,
                series = chain.map { UniverseSeries(title = it.label ?: it.qid) }
            ),
            matchedSeries = UniverseSeries(title = chain[position].label ?: seriesQid),
            position = position + 1
        )
    }

    /**
     * One fetch with the 429 retry policy (spec-26 T3): a rate-limited
     * response is retried with exponential backoff + jitter up to
     * [maxAttempts] tries; the final response (any status) is returned as-is
     * — a 429 past the limit carries an empty body, so every caller degrades
     * silently and the next book open repeats the resolution.
     */
    private suspend fun fetchWithRetry(url: String): WikidataResponse {
        var attempt = 0
        while (true) {
            val response = fetch(url)
            if (!WikidataRetryPolicy.shouldRetry(attempt, maxAttempts, response.statusCode)) {
                if (response.statusCode == WikidataRetryPolicy.HTTP_TOO_MANY_REQUESTS) {
                    diagnostic?.invoke(ResolutionDiagnostic.THROTTLED)
                }
                return response
            }
            delay(retryDelayMs(attempt))
            attempt++
        }
    }

    private suspend fun search(title: String): List<String>? {
        for (language in languages) {
            val json = fetchWithRetry(searchUrl(language, title)).body
            if (json.isBlank()) continue
            val ids = WikidataParser.searchHitIds(json)
            if (ids.isNotEmpty()) return ids.take(maxCandidates)
        }
        return null
    }

    /**
     * Spec-26 T2 (#176) — the author-aware work search. The author name is
     * resolved to its Wikidata QID (verified by its label against the book's
     * author — same normalization on both sides), then the work is searched
     * by CirrusSearch with `haswbstatement:P50=<qid>` + the normalized title
     * tokens. The label-search endpoint cannot take the author (its tokens
     * are ANDed against labels only, so the author name would kill the hit);
     * CirrusSearch indexes the P50 claims, so the author narrows the result
     * at search time. An unresolvable or unverified author, or an empty
     * narrowed search, contributes nothing — the caller falls through to the
     * plain title pass.
     */
    private suspend fun searchByAuthor(title: String, author: String): List<String>? {
        val authorQid = resolveAuthorQid(author) ?: return null
        val tokens = MergeKey.normalizeTitle(title)
            .split(" ")
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val json = fetchWithRetry(cirrusSearchUrl(authorQid, tokens.joinToString(" "))).body
        if (json.isBlank()) return null
        return WikidataParser.cirrusHitIds(json).takeIf { it.isNotEmpty() }
    }

    /**
     * Resolves the book's author to a Wikidata QID: wbsearchentities uk → ru
     * → en, verifying each candidate by its label against the book's author
     * (the same [CollectionMatcher.normalizeAuthor] rule on both sides — an
     * author with spaces, diacritics or apostrophes compares identically).
     * Returns null when no language finds an agreeing author — the caller
     * falls through, never resolving against a wrong author.
     */
    private suspend fun resolveAuthorQid(author: String): String? {
        for (language in languages) {
            val json = fetchWithRetry(authorSearchUrl(language, author)).body
            if (json.isBlank()) continue
            for (qid in WikidataParser.searchHitIds(json)) {
                if (authorNameMatches(qid, author)) return qid
            }
        }
        return null
    }

    /** True when the author entity's label (uk → ru → en) agrees with the
     *  book's author under the shared normalization. */
    private suspend fun authorNameMatches(authorQid: String, bookAuthor: String): Boolean {
        val json = fetchEntity(authorQid) ?: return false
        val label = WikidataParser.label(json, authorQid, languages) ?: return false
        return CollectionMatcher.normalizeAuthor(label) == CollectionMatcher.normalizeAuthor(bookAuthor)
    }

    /**
     * Spec-26 T1 (#175) — the translation fallback. Fires only after the
     * direct uk → ru → en search came back empty: the uk title is translated
     * into ru and en (ML Kit via the [TitleTranslator] seam) and each
     * translated string is searched in its own language, first hit wins. A
     * missing translator, a blank/identity translation, a failed fetch or an
     * empty translated search contributes nothing — the resolution stays
     * silent.
     */
    private suspend fun searchTranslated(title: String): List<String>? {
        val translator = translator ?: return null
        for (language in TRANSLATION_TARGETS) {
            if (language !in languages) continue
            val translated = translator.translate(title, language) ?: continue
            if (translated.isBlank() || translated == title) continue
            val json = fetchWithRetry(searchUrl(language, translated)).body
            if (json.isBlank()) continue
            val ids = WikidataParser.searchHitIds(json)
            if (ids.isNotEmpty()) return ids.take(maxCandidates)
        }
        return null
    }

    private suspend fun authorMatches(workQid: String, bookAuthor: String): Boolean {
        val workJson = fetchEntity(workQid) ?: return false
        val authorIds = WikidataParser.authorIds(workJson, workQid)
        if (authorIds.isEmpty()) return false
        val labelsJson = fetchEntity(authorIds.joinToString("|")) ?: return false
        val normalizedAuthor = CollectionMatcher.normalizeAuthor(bookAuthor)
        return authorIds.any { authorId ->
            val label = WikidataParser.label(labelsJson, authorId, languages)
            label != null && CollectionMatcher.normalizeAuthor(label) == normalizedAuthor
        }
    }

    /**
     * Walks the series' P155 (follows) links to the chain head, then the
     * P156 (followed by) links from the head to build the reading order.
     * Bounded ([maxChainHops] each way) and loop-safe (a visited id stops
     * the walk). Returns the ordered chain, or null when the book's series
     * cannot be placed in it.
     */
    private suspend fun buildChain(seriesQid: String): List<ChainSeries>? {
        val byQid = mutableMapOf<String, ChainSeries>()
        suspend fun info(qid: String): ChainSeries? = byQid[qid] ?: fetchSeries(qid)?.also { byQid[qid] = it }

        // Walk follows (P155) to the head.
        var head = seriesQid
        var current = seriesQid
        var hops = 0
        while (hops++ < maxChainHops) {
            val info = info(current) ?: return null
            val previous = info.follows.firstOrNull { it != current && it !in byQid } ?: break
            head = previous
            current = previous
        }
        // Walk followed-by (P156) from the head to build the order.
        val ordered = mutableListOf<String>()
        current = head
        hops = 0
        while (hops++ < maxChainHops) {
            if (current in ordered) break
            ordered += current
            val info = info(current) ?: break
            val next = info.followedBy.firstOrNull { it != current && it !in ordered } ?: break
            current = next
        }
        if (seriesQid !in ordered) return null
        return ordered.mapNotNull { byQid[it] }
    }

    private suspend fun fetchSeries(qid: String): ChainSeries? {
        val json = fetchEntity(qid) ?: return null
        return ChainSeries(
            qid = qid,
            label = WikidataParser.label(json, qid, languages),
            follows = WikidataParser.followsIds(json, qid),
            followedBy = WikidataParser.followedByIds(json, qid)
        )
    }

    private suspend fun fetchEntity(ids: String): String? =
        fetchWithRetry(entitiesUrl(ids)).body.takeIf { it.isNotBlank() }

    private fun searchUrl(language: String, title: String): String =
        "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json" +
            "&language=$language&uselang=$language&type=item&search=${encode(title)}"

    /** The author-name search (wbsearchentities) — the author QID resolution. */
    private fun authorSearchUrl(language: String, author: String): String =
        "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json" +
            "&language=$language&uselang=$language&type=item&search=${encode(author)}"

    /**
     * The CirrusSearch work query narrowed to one author: the P50 statement
     * constraint + the normalized title tokens (spec-26 T2).
     */
    private fun cirrusSearchUrl(authorQid: String, titleTokens: String): String =
        "https://www.wikidata.org/w/api.php?action=query&list=search&format=json" +
            "&srnamespace=0&srlimit=$maxCandidates" +
            "&srsearch=${encode("haswbstatement:P50=$authorQid $titleTokens")}"

    private fun entitiesUrl(ids: String): String =
        "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json" +
            "&ids=$ids&props=claims|labels"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        /** The translated-search targets, ru first (the Wikidata anchor). */
        val TRANSLATION_TARGETS = listOf("ru", "en")
    }

    /** One series of the resolved chain: its id, label and chain neighbors. */
    private data class ChainSeries(
        val qid: String,
        val label: String?,
        val follows: List<String>,
        val followedBy: List<String>
    )
}

/**
 * One Wikidata API response: HTTP status + body. The body is "" on any
 * failure (non-200 or a request error — the degrade-never-throw convention
 * of the adapter seam); the status lets the 429 retry policy (spec-26 T3)
 * tell a rate-limited response from any other failure.
 */
/**
 * Why a resolution failed — surfaced only through the provider's
 * [WikidataSeriesProvider.diagnostic] callback (spec-26 T4): the residual-
 * measurement harness classifies catalog misses by cause. [THROTTLED] is
 * emitted per request that exhausts its 429 retry budget; the others once
 * per resolve, at the first failure point.
 */
enum class ResolutionDiagnostic {
    /** No candidates in any language — the work is not on Wikidata. */
    SEARCH_MISS,
    /** Candidates existed, but none's P50 author agreed with the book's. */
    AUTHOR_MISMATCH,
    /** The work has no P179 series claim. */
    NO_SERIES_CLAIM,
    /** The series exists but its P155/P156 chain could not place it. */
    CHAIN_UNPLACEABLE,
    /** A request exhausted its 429 retry budget (rate-limited). */
    THROTTLED
}

data class WikidataResponse(
    val statusCode: Int,
    val body: String
)

/** One series of a resolved universe view (the provider seam's result). */
data class UniverseResolution(
    val universe: UniverseList,
    val matchedSeries: UniverseSeries,
    val position: Int
)

/**
 * Spec-25 — the provider seam behind the lazy universe resolution: turns a
 * book the curated set does not know into a curated-shaped universe view
 * (or nothing). The curated asset is implicitly the first provider; a
 * Wikidata provider ([WikidataSeriesProvider]) slots behind it. Any
 * failure contributes nothing.
 */
interface SeriesUniverseProvider {
    /** @return the universe view of the book's series, or null (silent). */
    suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution?
}
