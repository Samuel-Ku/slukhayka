package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A (spec `2026-09-10-remove-4read-source`) — LibriVox bulk depth on the
 * T1-verified transports, no new endpoint:
 * - the librivox.org feed admits every MAPPED language (the tags map in the
 *   registry already carries "Ukrainian"), an unknown language keeps an
 *   honest absent language rather than hiding the record (US17);
 * - the catalogue is PAGINATED through the api's own `offset` parameter,
 *   bounded by [LibrarySeeder]-style budgets — never an unbounded crawl.
 */
class LibriVoxCatalogTest {

    private fun fixture(name: String): String {
        val resource = javaClass.classLoader?.getResource("fixtures/$name")
            ?: throw IllegalStateException("Missing fixture $name")
        return resource.readText()
    }

    private fun url(offset: Int, limit: Int) =
        "https://librivox.org/api/feed/audiobooks/?format=json&limit=$limit&offset=$offset"

    @Test
    fun `catalog admits ukrainian records through the mapped language table`() = runBlocking {
        // Same live mixed payload (English + German): with the language table
        // admitted, the German records keep their real language instead of
        // being dropped, and the English ones stay as before.
        val adapter = LibriVoxAdapter(
            FakeFetcher(
                mapOf(url(0, 8) to fixture("librivox-api-feed-mixed.json"))
            )
        )

        val cards = adapter.fetchCatalog(8)

        // All 8 records are admitted: 6 English + 2 German (mapped, not hidden).
        assertEquals(8, cards.size)
        assertTrue(cards.all { it.language.isNotBlank() })
        assertTrue(cards.all { it.sourceId == "librivox" })
    }

    @Test
    fun `unknown api language keeps the card with an absent language`() = runBlocking {
        // A crafted record whose language word has no table entry: the card
        // survives with language = "" — visible under any selection (US17),
        // never hidden, never guessed.
        val json = """
            {"books":[
              {"title":"Книга","language":"Swahili","url_librivox":"https://librivox.org/x",
               "url_zip_file":"https://archive.org/compress/knyha_2601_librivox/128kb/mp3.zip",
               "totaltimesecs":120.0,
               "authors":[{"first_name":"Хтось","last_name":"Інший"}]}
            ]}
        """.trimIndent()
        val adapter = LibriVoxAdapter(FakeFetcher(mapOf(url(0, 1) to json)))

        val cards = adapter.fetchCatalog(1)

        assertEquals(1, cards.size)
        assertEquals("", cards.single().language)
    }

    @Test
    fun `catalog pages through the api offset until the limit`() = runBlocking {
        val page1 = """
            {"books":[
              {"title":"Книга А","language":"English","url_librivox":"https://librivox.org/a",
               "url_zip_file":"https://archive.org/compress/knyha_a_2601_librivox/128kb/mp3.zip",
               "totaltimesecs":60,"authors":[{"first_name":"Автор","last_name":"А"}]}
            ]}
        """.trimIndent()
        val page2 = """{"books":[]}"""
        val fetcher = FakeFetcher(mapOf(url(0, 3) to page1, url(3, 3) to page2))

        val cards = LibriVoxAdapter(fetcher).fetchCatalog(3)

        // Page 1 has one admitted record, page 2 is empty -> the pass stops.
        assertEquals(1, cards.size)
        assertEquals("Книга А", cards.single().title)
        assertEquals("https://archive.org/details/knyha_a_2601_librivox", cards.single().url)
    }
}
