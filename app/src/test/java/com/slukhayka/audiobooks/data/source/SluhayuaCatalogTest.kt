package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф2 (spec `2026-09-10-remove-4read-source`) — sluhayua catalogue depth.
 *
 * The `/find/allcards` endpoint is the T1-verified shape; paging it with
 * `page=N` (already used by the feed cursor) lets the union enumerate the
 * whole catalogue instead of page 1 only. Fixtures mirror the live JSON
 * (`pageCount` included). No network.
 */
class SluhayuaCatalogTest {

    private val page1 = """{"cards":[
      {"_id":1,"slug":"a","bookName":"Книга А","bookAuthor":["Автор А"],"audioAuthor":["Диктор А"],"kindSrc":"/uploads/a.png"},
      {"_id":2,"slug":"b","bookName":"Книга Б","bookAuthor":["Автор Б"],"audioAuthor":["Диктор Б"],"kindSrc":"/uploads/b.png"}
    ],"pageCount":3}"""

    private val page2 = """{"cards":[
      {"_id":3,"slug":"c","bookName":"Книга В","bookAuthor":["Автор В"],"audioAuthor":["Диктор В"],"kindSrc":"/uploads/c.png"}
    ],"pageCount":3}"""

    private val page3 = """{"cards":[
      {"_id":4,"slug":"d","bookName":"Книга Г","bookAuthor":["Автор Г"],"audioAuthor":["Диктор Г"],"kindSrc":"/uploads/d.png"}
    ],"pageCount":3}"""

    private fun url(page: Int) = "https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=$page"

    /** Records every gated request so the pass's bounds are assertable. */
    private class RecordingFetcher(responses: Map<String, String>) : FakeFetcher(responses) {
        val requested = mutableListOf<String>()

        override fun getText(
            url: String,
            extraHeaders: Map<String, String>,
            requestClass: SourceRequestClass,
            cacheTtlMillis: Long
        ): String {
            requested += url
            return super.getText(url, extraHeaders, requestClass, cacheTtlMillis)
        }
    }

    @Test
    fun `catalog pages through allcards until the limit`() = runBlocking {
        val fetcher = RecordingFetcher(mapOf(url(1) to page1, url(2) to page2))
        val adapter = SluhayuaAdapter(fetcher)

        val books = adapter.fetchCatalog(limit = 3)

        assertEquals(listOf("Книга А", "Книга Б", "Книга В"), books.map { it.title })
        assertEquals("https://sluhay.com.ua/3:c", books[2].url)
        // The third book lives on page 2 — page 3 must not be requested.
        assertEquals(listOf(url(1), url(2)), fetcher.requested)
    }

    @Test
    fun `catalog stops at the reported page count`() = runBlocking {
        val fetcher = RecordingFetcher(mapOf(url(1) to page1, url(2) to page2, url(3) to page3))
        val adapter = SluhayuaAdapter(fetcher)

        val books = adapter.fetchCatalog(limit = 100)

        assertEquals(4, books.size)
        // pageCount=3 is the contract: no page 4 request.
        assertEquals(listOf(url(1), url(2), url(3)), fetcher.requested)
    }

    @Test
    fun `catalog with a non-positive limit makes no request`() = runBlocking {
        val fetcher = RecordingFetcher(emptyMap())
        val adapter = SluhayuaAdapter(fetcher)

        assertTrue(adapter.fetchCatalog(limit = 0).isEmpty())
        assertTrue(fetcher.requested.isEmpty())
    }

    @Test
    fun `catalog breaks on an empty page`() = runBlocking {
        val fetcher = RecordingFetcher(mapOf(url(1) to page1))
        val adapter = SluhayuaAdapter(fetcher)

        val books = adapter.fetchCatalog(limit = 100)

        assertEquals(2, books.size)
        assertEquals(listOf(url(1), url(2)), fetcher.requested)
    }

    @Test
    fun `catalog keeps the XHR gate`() = runBlocking {
        val fetcher = RecordingFetcher(mapOf(url(1) to page1))
        val adapter = SluhayuaAdapter(fetcher)

        adapter.fetchCatalog(limit = 1)

        assertTrue(fetcher.recordedHeaders.all { it["X-Requested-With"] == "XMLHttpRequest" })
    }
}
