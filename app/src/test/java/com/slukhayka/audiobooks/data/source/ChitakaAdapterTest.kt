package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-50 T3 ChitakaAdapter. Markup mirrors real
 * chitaka.com.ua pages captured live in the T1 spike (committed under
 * `docs/wayfinder/research/fixtures/chitaka/`): lazyload listing cards and
 * the native `<audio>` book page. No network.
 */
class ChitakaAdapterTest {

    private val bookUrl = "https://chitaka.com.ua/knigi/1984/"
    private val listingUrl = "https://chitaka.com.ua/audioknyhy/"

    // /audioknyhy/ (trimmed): two audiobook cards (lazyload covers) + the
    // empty paginate-links block the live page renders (no server next link).
    private val listingPage = """
        <!-- SPIKE FIXTURE (spec-50 T1): live capture, trimmed. Original: /audioknyhy/ -->
        <title>Аудіокниги ⭐️ українською [слухати онлайн безкоштовно без реєстрації] | Читака</title>
        <a href="https://chitaka.com.ua/knigi/1984/" class="book-image"><img data-src="https://chitaka.com.ua/wp-content/uploads/2022/10/1984-237x362.jpg" alt="1984" title="1984"></a>
        <a href="https://chitaka.com.ua/knigi/1984/" class="recomend-book-title" title="1984"> 1984 </a>
        <a href="https://chitaka.com.ua/knigi/farbovanyj-lys/" class="book-image"><img data-src="https://chitaka.com.ua/wp-content/uploads/2022/10/lys-237x362.jpg" alt="Фарбований лис" title="Фарбований лис"></a>
        <a href="https://chitaka.com.ua/knigi/farbovanyj-lys/" class="recomend-book-title" title="Фарбований лис"> Фарбований лис </a>
        <div class="paginate-links"></div>
    """.trimIndent()

    // /knigi/1984/ (trimmed): the native audio tag with the direct mp3.
    private val bookPage = """
        <!-- SPIKE FIXTURE (spec-50 T1): live capture, trimmed. Original: /knigi/1984/ -->
        <title>«1984» Джордж Орвелл ⭐️ fb2, epub, rtf, txt, pdf українською читати онлайн, скачати книгу | Читака</title>
        <meta property="og:title" content="«1984» Джордж Орвелл" />
        <meta property="og:image" content="https://chitaka.com.ua/wp-content/uploads/2022/10/1984-og.jpg" />
        <audio class="lib_book_audio" style="width:100%;height:100%;"> <source src="https://chitaka.com.ua/wp-content/uploads/2022/10/1984.mp3"> </audio>
    """.trimIndent()

    // A text-only page: no audio tag at all (ebooks share the /knigi/ path).
    private val textPage = """
        <title>«Есеї» Хтось ⭐️ fb2, epub читати | Читака</title>
        <meta property="og:title" content="«Есеї» Хтось" />
    """.trimIndent()

    @Test
    fun `search is an honest empty list - query urls are robots-discouraged`() = runBlocking {
        // robots.txt: `Disallow: *?*` — no server search through query
        // strings; discovery rides fetchNew/fetchCatalog and the union.
        val adapter = ChitakaAdapter(FakeFetcher(emptyMap()))
        assertTrue(adapter.search("орвелл").isEmpty())
    }

    @Test
    fun `fetchNew parses lazyload cards with the limit`() = runBlocking {
        val adapter = ChitakaAdapter(FakeFetcher(mapOf(listingUrl to listingPage)))
        val results = adapter.fetchNew(limit = 1)
        assertEquals(1, results.size)
        assertEquals("1984", results[0].title)
        assertEquals("https://chitaka.com.ua/knigi/1984/", results[0].url)
        assertEquals(
            "https://chitaka.com.ua/wp-content/uploads/2022/10/1984-237x362.jpg",
            results[0].coverImageUrl
        )
        assertTrue(results.all { it.sourceId == "chitaka" })
    }

    @Test
    fun `listing card without a readable title never becomes a card`() = runBlocking {
        val adapter = ChitakaAdapter(
            FakeFetcher(mapOf(listingUrl to """<a href="https://chitaka.com.ua/knigi/x/" class="book-image"></a>"""))
        )
        assertTrue(adapter.fetchNew().isEmpty())
    }

    @Test
    fun `nav anchors never become cards`() = runBlocking {
        val adapter = ChitakaAdapter(
            FakeFetcher(
                mapOf(
                    listingUrl to
                        """<a href="https://chitaka.com.ua/zhanryi/" class="nav-link">Жанри</a>""" +
                        """<a href="https://chitaka.com.ua/knigi/1984/" class="book-image"></a>"""
                )
            )
        )
        assertTrue(adapter.fetchNew().isEmpty())
    }

    @Test
    fun `book page parses the native audio tag into one track`() = runBlocking {
        val adapter = ChitakaAdapter(FakeFetcher(mapOf(bookUrl to bookPage)))
        val detail = adapter.fetchBookPage(bookUrl)
        assertEquals("«1984»", detail.title)
        assertEquals("Джордж Орвелл", detail.author)
        assertEquals(
            "https://chitaka.com.ua/wp-content/uploads/2022/10/1984-og.jpg",
            detail.coverImageUrl
        )
        assertEquals(1, detail.chapters.size)
        assertEquals("«1984»", detail.chapters[0].title)
        assertEquals(
            "https://chitaka.com.ua/wp-content/uploads/2022/10/1984.mp3",
            detail.chapters[0].streamUrl
        )
    }

    @Test
    fun `text-only page is honestly empty - the audio-only boundary`() = runBlocking {
        val adapter = ChitakaAdapter(FakeFetcher(mapOf(bookUrl to textPage)))
        val detail = adapter.fetchBookPage(bookUrl)
        assertTrue(detail.chapters.isEmpty())
        assertEquals("«Есеї»", detail.title)
        assertEquals(bookUrl, detail.url)
    }

    @Test
    fun `unreachable page yields the empty honest detail`() = runBlocking {
        val adapter = ChitakaAdapter(FakeFetcher(emptyMap()))
        val detail = adapter.fetchBookPage(bookUrl)
        assertTrue(detail.chapters.isEmpty())
        assertEquals("", detail.title)
    }
}
