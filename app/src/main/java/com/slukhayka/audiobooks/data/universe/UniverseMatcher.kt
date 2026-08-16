package com.slukhayka.audiobooks.data.universe

import com.slukhayka.audiobooks.data.collections.CollectionMatcher

/**
 * Spec-25 (#171) — the pure universe matcher (pure JVM, no Android, no
 * network, no database): given a book's series claims (the source's series
 * title + series page URL), find the curated universe entry it belongs to
 * and its position inside it.
 *
 * Matching reuses the collections normalizer ([CollectionMatcher.normalizeTitle])
 * for titles (case-fold, punctuation strip, diacritics NFKD Cyrillic-
 * preserving, trailing annotation trim) and a light URL fold (trim,
 * trailing-slash strip, case-fold). URL match WINS over title match — the
 * source series URL is the primary key; normalized title aliases are the
 * fallback. A series the curated set does not know resolves to nothing —
 * the resolver then contributes nothing (silent).
 */
object UniverseMatcher {

    /** One resolved entry: the universe, the series and its 1-based position. */
    data class Match(
        val universe: UniverseList,
        val series: UniverseSeries,
        val position: Int
    )

    /**
     * @return the matching universe entry, or null when neither the URL nor
     * the normalized title matches any curated series.
     */
    fun resolve(
        universes: List<UniverseList>,
        seriesTitle: String,
        seriesUrl: String?
    ): Match? {
        val url = seriesUrl?.takeIf { it.isNotBlank() }
        if (url != null) {
            findUrl(universes, url)?.let { return it }
        }
        return findTitle(universes, seriesTitle)
    }

    private fun findUrl(universes: List<UniverseList>, url: String): Match? {
        val normalized = normalizeUrl(url)
        for (universe in universes) {
            for ((index, series) in universe.series.withIndex()) {
                if (series.urls.any { normalizeUrl(it) == normalized }) {
                    return Match(universe, series, index + 1)
                }
            }
        }
        return null
    }

    private fun findTitle(universes: List<UniverseList>, title: String): Match? {
        val normalized = normalizeSeriesTitle(title)
        for (universe in universes) {
            for ((index, series) in universe.series.withIndex()) {
                val keys = listOf(series.title) + series.aliases
                if (keys.any { normalizeSeriesTitle(it) == normalized }) {
                    return Match(universe, series, index + 1)
                }
            }
        }
        return null
    }

    /** The ONE title rule — the collections normalizer (case-fold, punctuation,
     *  diacritics Cyrillic-preserving, trailing-annotation trim). */
    fun normalizeSeriesTitle(title: String): String = CollectionMatcher.normalizeTitle(title)

    /** Light URL fold: trim, drop the trailing slash, case-fold. */
    fun normalizeUrl(url: String): String = url.trim().trimEnd('/').lowercase()
}
