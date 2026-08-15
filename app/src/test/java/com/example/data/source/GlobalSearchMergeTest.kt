package com.example.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure model seam (spec-10 T4): global-search result aggregation. Tests
 * external behaviour only — dedup across sources, one card per Work,
 * deterministic ordering, blank handling. No network, no Room.
 */
class GlobalSearchMergeTest {

    private fun book(
        title: String,
        author: String,
        sourceId: String,
        url: String = "https://example.com/$sourceId/$title"
    ) = SourceBook(title = title, author = author, url = url, sourceId = sourceId)

    @Test
    fun `same work found on two sources becomes one card with two badges`() {
        val results = listOf(
            book("Кобзар", "Тарас Шевченко", "4read", "https://4read.org/kobzar.html"),
            book("КОБЗАР", "Тарас Шевченко", "soundbooks", "https://sound-books.net/kobzar.html")
        )

        val merged = mergeGlobalSearchResults(results)

        assertEquals(1, merged.size)
        val card = merged.single()
        assertEquals("Кобзар", card.title)
        assertEquals(listOf("4read", "soundbooks"), card.sources.map { it.sourceId })
        assertEquals(listOf("4read", "Sound-Books"), card.sources.map { it.sourceName })
    }

    @Test
    fun `sluhayua result merges with the same work and carries the Sluhay badge`() {
        val results = listOf(
            book("Кобзар", "Тарас Шевченко", "4read", "https://4read.org/kobzar.html"),
            book("Кобзар", "Тарас Шевченко", "sluhayua", "https://sluhay.com.ua/4508492:taras-shevchenko-Єretik")
        )

        val merged = mergeGlobalSearchResults(results)

        assertEquals(1, merged.size)
        val card = merged.single()
        assertEquals(listOf("4read", "sluhayua"), card.sources.map { it.sourceId })
        assertEquals(listOf("4read", "Sluhay"), card.sources.map { it.sourceName })
    }

    @Test
    fun `different narrations merge into one card with both sources`() {
        val results = listOf(
            book("Кобзар", "Тарас Шевченко", "4read").copy(narrator = "Валерій Завалко"),
            book("Кобзар", "Тарас Шевченко", "soundbooks").copy(narrator = "Богдан Бенюк")
        )

        val merged = mergeGlobalSearchResults(results)

        // ADR-0010: the Work is bibliographic — both narrations are ONE card
        // carrying both sources (the narrator differentiates Editions, not
        // cards).
        assertEquals(1, merged.size)
        assertEquals(listOf("4read", "soundbooks"), merged.single().sources.map { it.sourceId })
    }

    @Test
    fun `blank author rows stay separate and never merge`() {
        val results = listOf(
            book("Кобзар", "Тарас Шевченко", "4read"),
            book("Кобзар", "", "soundbooks"),
            book("Кобзар", "", "lihtar", "https://lihtar.in.ua/other-url")
        )

        val merged = mergeGlobalSearchResults(results)

        // The author-less feed rows fall back to (sourceId, url): the two
        // lihtar/soundbooks rows are different urls, so three cards total.
        assertEquals(3, merged.size)
    }

    @Test
    fun `duplicate results from one source collapse into one badge`() {
        val dup = book("Кобзар", "Тарас Шевченко", "4read")
        val merged = mergeGlobalSearchResults(listOf(dup, dup.copy(url = "https://4read.org/kobzar.html#top")))

        assertEquals(1, merged.size)
        assertEquals(1, merged.single().sources.size)
    }

    @Test
    fun `results are ordered by title case-insensitively`() {
        val results = listOf(
            book("Темна матерія", "Блейк Крауч", "soundbooks"),
            book("Антологія", "Різні", "4read"),
            book("Зоряний пил", "Ніл Гейман", "4read")
        )

        val titles = mergeGlobalSearchResults(results).map { it.title }

        assertEquals(listOf("Антологія", "Зоряний пил", "Темна матерія"), titles)
    }

    @Test
    fun `junk rows with blank title or url are dropped`() {
        val results = listOf(
            book("", "Автор", "4read"),
            book("Книга", "Автор", "4read", ""),
            book("Нормальна", "Автор", "4read")
        )

        val merged = mergeGlobalSearchResults(results)

        assertEquals(1, merged.size)
        assertEquals("Нормальна", merged.single().title)
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(mergeGlobalSearchResults(emptyList()).isEmpty())
    }
}
