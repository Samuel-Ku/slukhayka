package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure model seam (spec-10 T4): global-search result aggregation. Tests
 * external behaviour only — dedup across sources, one card per Work,
 * deterministic ordering, blank handling. No network, no Room.
 */
class GlobalSearchMergeTest {

    @Test
    fun `sources expose shared Edition only when narrator identity matches`() {
        val merged = mergeGlobalSearchResults(
            listOf(
                book("Книга", "Автор", "soundbooks").copy(narrator = "Читець"),
                book("Книга", "Автор", "audiobookmp3").copy(narrator = "Читець"),
                book("Книга", "Автор", "fourread").copy(narrator = "Інший")
            )
        ).single()

        assertEquals(merged.sources[0].editionId, merged.sources[1].editionId)
        assertTrue(merged.sources[0].editionId != merged.sources[2].editionId)
    }

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
        assertEquals(listOf("soundbooks", "4read"), card.sources.map { it.sourceId })
        assertEquals(listOf("Sound-Books", "4read"), card.sources.map { it.sourceName })
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
        assertEquals(listOf("sluhayua", "4read"), card.sources.map { it.sourceId })
        assertEquals(listOf("Sluhay", "4read"), card.sources.map { it.sourceName })
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
        assertEquals(listOf("soundbooks", "4read"), merged.single().sources.map { it.sourceId })
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

    @Test
    fun `search result title is scrubbed of SEO suffixes`() {
        val merged = mergeGlobalSearchResults(
            listOf(book("Кобзар - аудіокнига слухати онлайн", "Тарас Шевченко", "4read"))
        )

        assertEquals("Кобзар", merged.single().title)
    }

    /**
     * Spec-42 #439 — the real effect: once [FourReadAdapter] takes the author
     * from the SECOND subtitle (not genres), a 4read search hit with the real
     * Cyrillic author merges into the same Work card as the book found on
     * sound-books under the same "title|author" merge key. With genres as the
     * author the cards would stay separate and search would look empty.
     */
    @Test
    fun `4read search hit with real author merges with the same work on another source`() = kotlinx.coroutines.runBlocking {
        val fourRead = FourReadAdapter(FakeFetcher(emptyMap(), fallback = """
            <html><body>
            <div class="poster has-overlay grid-item d-flex fd-column">
                <div class="poster__desc order-last">
                    <a href="https://4read.org/5359-taras-shevchenko-kobzar.html" class="poster__link"><div class="poster__title line-clamp">Кобзар</div></a>
                    <div class="poster__subtitle ws-nowrap">Українська література / Роман</div>
                    <div class="poster__subtitle ws-nowrap">Тарас Шевченко</div>
                </div>
            </div>
            </body></html>
        """))
        val fourReadBook = fourRead.search("Кобзар").single()

        val merged = mergeGlobalSearchResults(
            listOf(
                fourReadBook,
                book("Кобзар", "Тарас Шевченко", "soundbooks", "https://sound-books.net/kobzar.html")
            )
        )

        // One card, the real author, both sources — not a split 4read-only card.
        assertEquals(1, merged.size)
        assertEquals("Тарас Шевченко", merged.single().author)
        assertEquals(listOf("soundbooks", "4read"), merged.single().sources.map { it.sourceId })
    }
}
