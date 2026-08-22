package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM fixture tests for the spec-37 live collection source
 * (sluhay.com.ua popularity). Payload mirrors the real
 * `/find/allcards?sort=views&order=desc&page=1` shape (X-Requested-With gate):
 * `{ "cards": [{ "bookName": "...", "bookAuthor": ["..."] }] }`.
 * No network — the shared fetcher serves canned text (ADR-0006).
 */
class SluhayuaPopularSourceTest {

    private val popularFixture = """
        {
          "cards": [
            {
              "_id": 5312322,
              "slug": "vsevolod-nestajko-toreadori-z-vasyukivki",
              "bookName": "Тореадори з Васюківки",
              "bookAuthor": ["Всеволод Нестайко"],
              "audioAuthor": ["Андрій Сова"],
              "views": 343422
            },
            {
              "_id": 123,
              "slug": "till",
              "bookName": "Тигролови",
              "bookAuthor": ["Іван Багряний"],
              "audioAuthor": ["Ігор Мурашко"],
              "views": 212515
            },
            {
              "_id": 999,
              "slug": "no-author",
              "bookName": "Книга без автора",
              "bookAuthor": [" "],
              "views": 100
            },
            {
              "_id": 1000,
              "slug": "blank-title",
              "bookName": "   ",
              "bookAuthor": ["Леся Українка"],
              "views": 50
            }
          ],
          "pageCount": 10
        }
    """.trimIndent()

    @Test
    fun `parses the popular payload into one collection with title and primary author`() = runBlocking {
        val source = SluhayuaPopularSource(
            fetcher = FakeFetcher(mapOf(SluhayuaPopularSource.POPULAR_ENDPOINT to popularFixture))
        )

        val lists = source.fetchLiveCollections()

        assertEquals(1, lists.size)
        val list = lists.single()
        assertEquals("sluhayua-popular", list.id)
        assertEquals("Популярне у sluhay.com.ua", list.name)
        // Junk rows (blank author / blank title) contribute nothing.
        assertEquals(2, list.entries.size)
        assertEquals("Всеволод Нестайко", list.entries[0].author)
        assertEquals("Тореадори з Васюківки", list.entries[0].title)
        assertEquals("Іван Багряний", list.entries[1].author)
        assertEquals("Тигролови", list.entries[1].title)
    }

    @Test
    fun `sends the XHR header required by the gated endpoint`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(SluhayuaPopularSource.POPULAR_ENDPOINT to popularFixture))
        val source = SluhayuaPopularSource(fetcher = fetcher)

        source.fetchLiveCollections()

        assertEquals(1, fetcher.recordedHeaders.size)
        assertEquals("XMLHttpRequest", fetcher.recordedHeaders.single()["X-Requested-With"])
    }

    @Test
    fun `a fetch failure yields no collection - never throws`() = runBlocking {
        val source = SluhayuaPopularSource(fetcher = FakeFetcher(emptyMap()))

        assertTrue(source.fetchLiveCollections().isEmpty())
    }

    @Test
    fun `a changed upstream shape yields no collection`() = runBlocking {
        val source = SluhayuaPopularSource(
            fetcher = FakeFetcher(mapOf(SluhayuaPopularSource.POPULAR_ENDPOINT to """{"books": []}"""))
        )

        assertTrue(source.fetchLiveCollections().isEmpty())
    }

    @Test
    fun `limit caps the entries`() = runBlocking {
        val source = SluhayuaPopularSource(
            fetcher = FakeFetcher(mapOf(SluhayuaPopularSource.POPULAR_ENDPOINT to popularFixture)),
            limit = 1
        )

        val lists = source.fetchLiveCollections()

        assertEquals(1, lists.single().entries.size)
        assertEquals("Тореадори з Васюківки", lists.single().entries.single().title)
    }
}
