package com.example.data.universe

/**
 * Spec-25 (#171) — the curated universe data model (pure JVM).
 *
 * A [UniverseList] is one named world/cycle (Перший закон, Відьмак, …)
 * bundled as a static JSON asset. Each [UniverseSeries] is one series of
 * that universe: the canonical display [title], plus the matching keys —
 * [urls] (the source's series-page URLs, e.g. the 4read `xfsearch/cikl/…`
 * link — the PRIMARY key, URL match wins) and [aliases] (normalized-title
 * fallbacks). The ORDER of [series] is the universe's reading order: it
 * yields the precedes/follows relations.
 */
data class UniverseSeries(
    val title: String,
    val aliases: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    // Spec-26 T7: the series' last publication year (Wikidata P577, captured
    // at resolution) — the age signal of the tiered refresh rule; null for
    // curated series (their asset is exempt from the TTL anyway).
    val publicationYear: Int? = null
)

/** One curated universe: stable [id], display [name] and the ordered [series]. */
data class UniverseList(
    val id: String,
    val name: String,
    val series: List<UniverseSeries> = emptyList()
)
