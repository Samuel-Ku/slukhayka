package com.example.data.universe

import com.example.data.collections.CollectionMatcher
import java.net.URLEncoder

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
 * Best-effort and silent by design: no network, no search hits in any of
 * uk → ru → en (direct or translated), no author agreement, no P179, a
 * malformed response or an ambiguous candidate set all yield null — the
 * surfaces never degrade. The transport is injected (`fetchJson` returns
 * the raw JSON or "" on failure, the degrade-never-throw convention of the
 * adapter seam); fixture tests serve canned API responses by URL.
 */
class WikidataSeriesProvider(
    private val fetchJson: suspend (String) -> String,
    private val languages: List<String> = listOf("uk", "ru", "en"),
    private val maxCandidates: Int = 3,
    private val maxChainHops: Int = 8,
    private val translator: TitleTranslator? = null
) : SeriesUniverseProvider {

    override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
        if (bookTitle.isBlank() || bookAuthor.isBlank()) return null
        // 1. Search the work title, uk → ru → en (the first language with
        //    hits wins — never a union, so ambiguity stays contained). When
        //    all three come back empty, the uk title is translated uk → ru/en
        //    (spec-26 T1, ML Kit via the [TitleTranslator] seam) and the
        //    search is retried with the translated string — a book whose
        //    only Wikidata labels are ru/en still resolves.
        val candidates = search(bookTitle) ?: searchTranslated(bookTitle) ?: return null
        // 2. Candidate verification: the first candidate whose P50 author
        //    agrees with the book's author wins; none agreeing → no resolution.
        val workQid = candidates.firstOrNull { authorMatches(it, bookAuthor) } ?: return null
        // 3. P179 → the series item.
        val workJson = fetchEntity(workQid) ?: return null
        val seriesQid = WikidataParser.seriesIds(workJson, workQid).firstOrNull() ?: return null
        // 4. The series' P155/P156 chain, bounded in both directions; the
        //    chain head names the universe and anchors its id.
        val chain = buildChain(seriesQid) ?: return null
        val position = chain.indexOfFirst { it.qid == seriesQid }
        if (position < 0) return null
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

    private suspend fun search(title: String): List<String>? {
        for (language in languages) {
            val json = fetchJson(searchUrl(language, title))
            if (json.isBlank()) continue
            val ids = WikidataParser.searchHitIds(json)
            if (ids.isNotEmpty()) return ids.take(maxCandidates)
        }
        return null
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
            val json = fetchJson(searchUrl(language, translated))
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
        fetchJson(entitiesUrl(ids)).takeIf { it.isNotBlank() }

    private fun searchUrl(language: String, title: String): String =
        "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json" +
            "&language=$language&uselang=$language&type=item&search=${encode(title)}"

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
