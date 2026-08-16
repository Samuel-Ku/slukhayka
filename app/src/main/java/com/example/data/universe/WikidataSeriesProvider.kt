package com.example.data.universe

import com.example.data.collections.CollectionMatcher
import com.example.data.source.FetchResult
import java.net.URLEncoder
import kotlin.random.Random
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
 * Best-effort and silent by design: no network, no search hits in any of
 * uk → ru → en, no translation (spec-26 T1 — a [TitleTranslator] fallback
 * searches the translated title when the direct search is empty), no author
 * agreement, no P179, a malformed response or an ambiguous candidate set all
 * yield null — the surfaces never degrade. The transport is injected
 * (`fetchJson` returns a [FetchResult] — body "" on failure, the degrade-
 * never-throw convention of the adapter seam); a rate-limited response (429)
 * is retried with exponential backoff and jitter up to [maxAttempts], then
 * silently given up on (spec-26 T3). Fixture tests serve canned API
 * responses by URL.
 *
 * Best-effort is preserved for the UI: the failure reasons surface only
 * through the optional [diagnostic] callback (spec-26 T4) — the residual-
 * measurement harness classifies catalog misses by cause; the surfaces
 * themselves still degrade to nothing.
 */
class WikidataSeriesProvider(
    private val fetchJson: suspend (String) -> FetchResult,
    private val languages: List<String> = listOf("uk", "ru", "en"),
    private val maxCandidates: Int = 3,
    private val maxChainHops: Int = 8,
    // Spec-26 T4: research/eval support — invoked once per resolve at the
    // first failure point (and per exhausted-429 request). Null in
    // production wiring; the residual harness collects it to classify
    // catalog misses by cause.
    private val diagnostic: ((ResolutionDiagnostic) -> Unit)? = null,
    // Spec-26 T3: the retry budget for rate-limited (429) requests — other
    // statuses and empty bodies are final and never retried. Exponential
    // backoff between attempts, jittered so a fleet of clients does not
    // retry in lockstep.
    private val maxAttempts: Int = 3,
    private val retryBaseMillis: Long = 500,
    private val retryMaxMillis: Long = 5_000,
    // Injectable for deterministic fixture tests (real default = delay).
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    // Spec-26 T1: the title-translation fallback (on-device ML Kit). Null —
    // the path is disabled; the source titles are Ukrainian but Wikidata
    // items often carry only ru/en labels, so a translated search catches
    // them.
    private val translator: TitleTranslator? = null
) : SeriesUniverseProvider {

    override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
        if (bookTitle.isBlank() || bookAuthor.isBlank()) return null
        // 1. Search the work title + author tokens, uk → ru → en (the first
        //    language with hits wins — never a union, so ambiguity stays
        //    contained; the author token disambiguates same-titled works at
        //    the search stage, and the P50 check below confirms it). When
        //    every direct search is empty, the translated-title fallback
        //    fires (only then — a direct hit never pays for a translation).
        val candidates = search(bookTitle, bookAuthor) ?: searchTranslated(bookTitle, bookAuthor)
            ?: run { diagnostic?.invoke(ResolutionDiagnostic.SEARCH_MISS); return null }
        // 2. Candidate verification: the first candidate whose P50 author
        //    agrees with the book's author wins; none agreeing → no resolution.
        val workQid = candidates.firstOrNull { authorMatches(it, bookAuthor) }
            ?: run { diagnostic?.invoke(ResolutionDiagnostic.AUTHOR_MISMATCH); return null }
        // 3. The series item: the direct P179 claim, else the underlying
        //    work of an edition (P629), else the main subject (P921) when it
        //    is itself a series — so editions and companion works resolve too.
        val seriesQid = findSeriesQid(workQid)
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
        val universeName = head.label
            ?: run { diagnostic?.invoke(ResolutionDiagnostic.CHAIN_UNPLACEABLE); return null }
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

    private suspend fun search(title: String, author: String): List<String>? {
        // Spec-26 T2: the query carries the title AND the author tokens, so
        // same-titled works by different authors rank apart at the search
        // stage (a blank author keeps the query title-only).
        val query = if (author.isBlank()) title else "$title $author"
        for (language in languages) {
            val body = fetch(searchUrl(language, query)).body
            if (body.isBlank()) continue
            val ids = WikidataParser.searchHitIds(body)
            if (ids.isNotEmpty()) return ids.take(maxCandidates)
        }
        return null
    }

    /**
     * The translated-title fallback (spec-26 T1): translate the title to ru,
     * then en, and search each translation through the same language ladder
     * (still with the author token, spec-26 T2). Each hop is independent and
     * failure-tolerant; a translation that fails, comes back blank or
     * unchanged contributes nothing. The translated path reuses the SAME
     * candidate verification — the P50 author check applies to translated
     * hits exactly like direct ones.
     */
    private suspend fun searchTranslated(title: String, author: String): List<String>? {
        val translator = translator ?: return null
        for (target in listOf("ru", "en")) {
            val translated = translator.translate(title, target) ?: continue
            if (translated.isBlank() || translated.equals(title, ignoreCase = true)) continue
            val ids = search(translated, author)
            if (!ids.isNullOrEmpty()) return ids
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
     * The work's series item, three ways: the direct P179 claim first; an
     * edition (P629) falls back to its underlying work's P179; a work whose
     * main subject (P921) is itself a series resolves to that series — gated
     * on P31 so a random subject never fabricates a bogus universe. Any
     * failing hop contributes nothing.
     */
    private suspend fun findSeriesQid(workQid: String): String? {
        val workJson = fetchEntity(workQid) ?: return null
        // 1. The direct P179 claim.
        WikidataParser.seriesIds(workJson, workQid).firstOrNull()?.let { return it }
        // 2. An edition (P629) resolves through its underlying work's P179.
        for (editionOf in WikidataParser.editionOfIds(workJson, workQid)) {
            val underlying = fetchEntity(editionOf) ?: continue
            WikidataParser.seriesIds(underlying, editionOf).firstOrNull()?.let { return it }
        }
        // 3. The main subject (P921) when it is itself a series.
        for (subject in WikidataParser.mainSubjectIds(workJson, workQid)) {
            val subjectJson = fetchEntity(subject) ?: continue
            if (WikidataParser.instanceOfIds(subjectJson, subject).any { it in SERIES_CLASSES }) return subject
        }
        return null
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
        fetch(entitiesUrl(ids)).body.takeIf { it.isNotBlank() }

    /**
     * Spec-26 T3 — the rate-limit retry wrapper around the injected
     * transport: only a 429 is retried (exponential backoff, jittered,
     * bounded by [maxAttempts]); any other status or empty body is final —
     * retrying would not cure it and only burns the API budget. Exhausting
     * the budget returns the last 429, whose empty body degrades the
     * resolution silently (the next book open retries).
     */
    private suspend fun fetch(url: String): FetchResult {
        var attempt = 1
        while (true) {
            val result = fetchJson(url)
            if (result.status != 429) return result
            if (attempt >= maxAttempts) {
                diagnostic?.invoke(ResolutionDiagnostic.THROTTLED)
                return result
            }
            sleep(backoffMillis(attempt))
            attempt++
        }
    }

    /** The jittered exponential backoff for attempt N (1-based). */
    private fun backoffMillis(attempt: Int): Long {
        val base = minOf(retryMaxMillis, retryBaseMillis shl (attempt - 1))
        val jitter = 0.75 + Random.nextDouble() * 0.5 // 0.75..1.25 of the base
        return (base * jitter).toLong()
    }

    private fun searchUrl(language: String, title: String): String =
        "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json" +
            "&language=$language&uselang=$language&type=item&search=${encode(title)}"

    private fun entitiesUrl(ids: String): String =
        "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json" +
            "&ids=$ids&props=claims|labels"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * P31 classes that identify an item as a book-ish series — the gate for
     * the P921 (main subject) fallback. Q7725310 is "series of creative
     * works", Q277759 is "book series" (verified against the live API).
     */
    private val SERIES_CLASSES = setOf("Q7725310", "Q277759")

    /** One series of the resolved chain: its id, label and chain neighbors. */
    private data class ChainSeries(
        val qid: String,
        val label: String?,
        val follows: List<String>,
        val followedBy: List<String>
    )
}

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
    /** The work has no P179 series, no P629 edition-of, no P921 series subject. */
    NO_SERIES_CLAIM,
    /** The series exists but its P155/P156 chain could not place it. */
    CHAIN_UNPLACEABLE,
    /** A request exhausted its 429 retry budget (rate-limited). */
    THROTTLED
}

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
