package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM fixture tests for the spec-16 follow-up live collection source
 * (OpenLibrary trending). Markup mirrors the real `/trending/now.json` shape
 * (trimmed from a live fetch): `works[]` with `title` + `author_name[]`.
 * No network — the shared fetcher serves canned text (ADR-0006).
 */
class OpenLibraryTrendingSourceTest {

    private val trendingFixture = """
        {
          "query": "/trending/now",
          "works": [
            {
              "key": "/works/OL17795654W",
              "title": "The Burnout Society",
              "author_name": ["Byung-Chul Han", "Han, Byung-Chul"],
              "edition_count": 10
            },
            {
              "key": "/works/OL13787473W",
              "title": "The baby care book",
              "author_name": ["Jeremy N. Friedman"]
            },
            {
              "key": "/works/OL123W",
              "title": "No author here",
              "edition_count": 1
            },
            {
              "key": "/works/OL124W",
              "title": "",
              "author_name": ["Nobody"]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses the trending payload into one collection with title and primary author`() = runBlocking {
        val source = OpenLibraryTrendingSource(fetcher = FakeFetcher(mapOf(OpenLibraryTrendingSource.TRENDING_ENDPOINT to trendingFixture)))

        val lists = source.fetchLiveCollections()

        assertEquals(1, lists.size)
        val list = lists.single()
        assertEquals("live-trending", list.id)
        assertEquals("Популярне зараз", list.name)
        // Junk rows (missing author / blank title) contribute nothing.
        assertEquals(2, list.entries.size)
        assertEquals("Byung-Chul Han", list.entries[0].author)
        assertEquals("The Burnout Society", list.entries[0].title)
        assertEquals("Jeremy N. Friedman", list.entries[1].author)
    }

    @Test
    fun `a fetch failure yields no collection - never throws`() = runBlocking {
        // Unknown URL → the fetcher serves its empty fallback.
        val source = OpenLibraryTrendingSource(fetcher = FakeFetcher(emptyMap()))

        assertTrue(source.fetchLiveCollections().isEmpty())
    }

    @Test
    fun `a changed upstream shape yields no collection`() = runBlocking {
        val source = OpenLibraryTrendingSource(
            fetcher = FakeFetcher(mapOf(OpenLibraryTrendingSource.TRENDING_ENDPOINT to """{"books": []}"""))
        )

        assertTrue(source.fetchLiveCollections().isEmpty())
    }

    @Test
    fun `limit caps the entries`() = runBlocking {
        val source = OpenLibraryTrendingSource(
            fetcher = FakeFetcher(mapOf(OpenLibraryTrendingSource.TRENDING_ENDPOINT to trendingFixture)),
            limit = 1
        )

        val lists = source.fetchLiveCollections()

        assertEquals(1, lists.single().entries.size)
        assertEquals("The Burnout Society", lists.single().entries.single().title)
    }
}
