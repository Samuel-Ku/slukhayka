package com.slukhayka.audiobooks.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CatalogSeriesIndex] (spec-28 #189): the «Серії» index
 * flattens every series across the catalogue sections and deduplicates by
 * URL. The aggregation is the screen's only data seam — no new source, the
 * index re-shapes what [CatalogParser] already produces.
 */
class CatalogSeriesIndexTest {

    private val maksymTemnyi = CatalogSeries(
        title = "Максим Темний",
        url = "https://4read.org/xfsearch/cikl/maksym-temnyj/",
        coverImageUrl = "https://4read.org/uploads/posts/2026-05/medium/4_ks_mt7.webp"
    )

    private val pershyiZakon = CatalogSeries(
        title = "Перший закон",
        url = "https://4read.org/xfsearch/cikl/pervyj-zakon/",
        coverImageUrl = null
    )

    private val vidvaha = CatalogSeries(
        title = "Відвага",
        url = "https://4read.org/xfsearch/cikl/vidvaha/",
        coverImageUrl = "https://4read.org/uploads/posts/2026-04/medium/vidvaha.webp"
    )

    @Test
    fun `aggregates series across sections in first-seen order`() {
        val sections = listOf(
            CatalogSection(
                title = "Новинки", books = emptyList(), series = listOf(maksymTemnyi, pershyiZakon),
                id = CatalogSectionId.NEW_ARRIVALS
            ),
            CatalogSection(title = "Цикли", series = listOf(vidvaha), id = CatalogSectionId.SERIES)
        )

        val result = CatalogSeriesIndex.aggregate(sections)

        assertEquals(listOf(maksymTemnyi, pershyiZakon, vidvaha), result)
    }

    @Test
    fun `deduplicates by URL keeping the first occurrence`() {
        // The same series featured by two books — the second occurrence must
        // not duplicate the entry, and the FIRST cover wins.
        val sameSeriesOtherCover = maksymTemnyi.copy(coverImageUrl = "https://4read.org/uploads/posts/2026-06/medium/other.webp")
        val sections = listOf(
            CatalogSection(
                title = "Новинки", books = emptyList(), series = listOf(maksymTemnyi, vidvaha),
                id = CatalogSectionId.NEW_ARRIVALS
            ),
            CatalogSection(title = "Цикли", series = listOf(sameSeriesOtherCover, pershyiZakon), id = CatalogSectionId.SERIES)
        )

        val result = CatalogSeriesIndex.aggregate(sections)

        assertEquals(3, result.size)
        assertEquals("https://4read.org/uploads/posts/2026-05/medium/4_ks_mt7.webp", result[0].coverImageUrl)
        assertEquals(listOf("Максим Темний", "Відвага", "Перший закон"), result.map { it.title })
    }

    @Test
    fun `empty input yields an empty index`() {
        assertTrue(CatalogSeriesIndex.aggregate(emptyList()).isEmpty())
    }

    @Test
    fun `sections without series yield an empty index`() {
        val sections = listOf(
            CatalogSection(title = "Новинки", books = emptyList(), id = CatalogSectionId.NEW_ARRIVALS),
            CatalogSection(title = "Популярне", books = emptyList(), id = CatalogSectionId.POPULAR)
        )

        assertTrue(CatalogSeriesIndex.aggregate(sections).isEmpty())
    }

    @Test
    fun `series with a blank url still surface once`() {
        // A degenerate entry (no URL) must still show up — never silently
        // swallowed by the index — but two blank-URL entries share the same
        // dedup key and collapse to the first.
        val sections = listOf(
            CatalogSection(
                title = "Цикли",
                series = listOf(
                    CatalogSeries("Без посилання", "", null),
                    CatalogSeries("Теж без посилання", "", null)
                ),
                id = CatalogSectionId.SERIES
            )
        )

        val result = CatalogSeriesIndex.aggregate(sections)
        assertEquals(1, result.size)
        assertEquals("Без посилання", result[0].title)
    }
}
