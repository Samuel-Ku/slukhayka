package com.example.data.universe

/**
 * Spec-26 T6 (#180) — the curated-asset seed: pours the bundled top-N
 * universe asset into the shared base at startup, so the base is populated
 * with the human-curated relations from day one instead of waiting for
 * per-book Wikidata write-backs.
 *
 * Idempotent by construction: ONE document per curated series, keyed
 * deterministically (`seed:` + the series' URL key, else its normalized
 * title — the same identity the local matcher uses, URL first), and the
 * store's put replaces a document key, never duplicates. A re-seed on a
 * later launch writes the same documents. Best-effort and silent: no store
 * (no Firebase keys), a failing store, or a failing write contributes
 * nothing — the local curated asset keeps working either way.
 *
 * The `seed:` key space never collides with the workId key space of the
 * read/write-back path (workIds are mergeKeys `title|author|narrator`); the
 * app itself never reads seed documents — they exist for the base's other
 * consumers (thin clients, diagnostics) and are the write-side half of
 * AC3's "curated asset pours into Firestore at first launch".
 */
object CuratedSeed {

    /** One document per curated series, keyed `seed:<series key>`. */
    suspend fun seed(
        store: SharedUniverseStore?,
        curated: List<UniverseList>,
        now: () -> Long = System::currentTimeMillis
    ) {
        val shared = store ?: return
        val stamp = now()
        for (universe in curated) {
            universe.series.forEachIndexed { index, series ->
                val key = seriesKey(series) ?: return@forEachIndexed
                // Defense-in-depth: the store's own contract is best-effort,
                // but a throwing implementation must never surface past the
                // seed either (AC5) — one bad document cannot abort the rest.
                runCatching {
                    shared.putResolution(
                        "seed:$key",
                        UniverseResolution(
                            universe = universe,
                            matchedSeries = series,
                            position = index + 1
                        ),
                        ResolutionProvenance(
                            source = ResolutionProvenance.SOURCE_CURATED,
                            authorVerified = true, // human-curated asset
                            resolvedAt = stamp
                        )
                    )
                }
            }
        }
    }

    /** The deterministic series identity: URL key first, normalized title second. */
    private fun seriesKey(series: UniverseSeries): String? {
        series.urls.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return UniverseMatcher.normalizeUrl(it) }
        return series.title.takeIf { it.isNotBlank() }?.let { UniverseMatcher.normalizeSeriesTitle(it) }
    }
}
