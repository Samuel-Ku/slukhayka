package com.slukhayka.audiobooks.data.catalog

/**
 * spec-28 (#189) — the «Серії» index aggregation. Flattens every
 * [CatalogSeries] across the catalogue sections into one browsable list,
 * deduplicated by URL (the first occurrence wins — a series featured by
 * several books keeps the cover of its first appearance). No new data source:
 * the index only re-shapes what [CatalogParser] already produces, so the
 * screen can never show a series the catalogue parser did not surface.
 *
 * Pure JVM (no Android dependencies), so dedup and empty-section behaviour
 * are unit-testable with plain JUnit — see `CatalogSeriesIndexTest`.
 */
object CatalogSeriesIndex {

    /** All series across the sections, deduplicated by URL, in first-seen order. */
    fun aggregate(sections: List<CatalogSection>): List<CatalogSeries> {
        val byUrl = LinkedHashMap<String, CatalogSeries>()
        for (section in sections) {
            for (series in section.series) {
                byUrl.putIfAbsent(series.url, series)
            }
        }
        return byUrl.values.toList()
    }
}
