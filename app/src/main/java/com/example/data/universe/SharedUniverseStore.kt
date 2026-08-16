package com.example.data.universe

/**
 * Spec-26 T5 — the shared universe-knowledge store behind a pure JVM seam:
 * the middle layer of the client-first read path Room → Firestore → Wikidata.
 * A resolution that another user already resolved and wrote back (T6) is
 * read here BEFORE the slow Wikidata fallback — a hit never pays for a
 * Wikidata resolution, and [SeriesUniverses] mirrors it into the local Room
 * cache so it stays instant and offline afterwards.
 *
 * The identity is the book's `workId` (the mergeKey of the works table,
 * `title|author|narrator` normalized, or the source book id) — stable
 * across users, and the same key the Room membership already uses. The
 * store is best-effort and silent by contract: a miss, a failure, or an
 * unreadable document all yield null — the caller simply falls through to
 * Wikidata exactly as before (spec-26 T5 AC3).
 */
interface SharedUniverseStore {
    /** The shared resolution of one work, or null on miss/failure. */
    suspend fun getResolution(workId: String): UniverseResolution?
}

/**
 * The Firestore document codec for a [UniverseResolution] — pure JVM so the
 * shape is unit-testable without Firebase. Document fields:
 *
 * ```
 * universeId:     String                  (e.g. "wd:Q11835640")
 * universeName:   String
 * series:         [ {title, url?}, ... ]  (the ordered universe chain)
 * matchedSeries:  {title, url?}           (the book's series)
 * position:       Long                    (1-based position in the chain)
 * ```
 *
 * Provenance (`source`, `authorVerified`, `resolvedAt`) lands with the write
 * path (spec-26 T6); reads ignore unknown fields, so older documents decode
 * fine. [fromMap] is defensive: any missing/mistyped required field, an
 * empty chain, or an out-of-range position yields null (a corrupt document
 * is a miss, never a crash).
 */
object SharedResolutionCodec {

    fun toMap(resolution: UniverseResolution): Map<String, Any> = mapOf(
        "universeId" to resolution.universe.id,
        "universeName" to resolution.universe.name,
        "series" to resolution.universe.series.map { seriesToMap(it) },
        "matchedSeries" to seriesToMap(resolution.matchedSeries),
        "position" to resolution.position.toLong()
    )

    fun fromMap(map: Map<String, Any>): UniverseResolution? {
        val universeId = map["universeId"] as? String ?: return null
        val universeName = map["universeName"] as? String ?: return null
        val seriesRaw = map["series"] as? List<*> ?: return null
        val series = mutableListOf<UniverseSeries>()
        for (raw in seriesRaw) {
            val item = raw as? Map<*, *> ?: return null
            series += seriesFromMap(item) ?: return null
        }
        if (series.isEmpty()) return null
        val matchedSeries = seriesFromMap(map["matchedSeries"] as? Map<*, *> ?: return null)
            ?: return null
        val position = (map["position"] as? Number)?.toInt() ?: return null
        if (position < 1 || position > series.size) return null
        return UniverseResolution(
            universe = UniverseList(id = universeId, name = universeName, series = series),
            matchedSeries = matchedSeries,
            position = position
        )
    }

    private fun seriesToMap(series: UniverseSeries): Map<String, Any> {
        val url = series.urls.firstOrNull()
        return if (url != null) mapOf("title" to series.title, "url" to url)
        else mapOf("title" to series.title)
    }

    private fun seriesFromMap(map: Map<*, *>): UniverseSeries? {
        val title = map["title"] as? String ?: return null
        val url = map["url"] as? String
        return UniverseSeries(title = title, urls = if (url != null) listOf(url) else emptyList())
    }
}
