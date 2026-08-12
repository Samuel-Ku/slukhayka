package com.example.data.source

import com.example.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-13 T2 SluhayAdapter (WebView-pattern source).
 * Markup mirrors the real sluhay.com book page captured live in the T1 spike
 * (`docs/wayfinder/research/fixtures/webview/sluhay-book-trohi-nenavisti.html`,
 * trimmed): og:title carries the « » <Site>» suffix, the Назва/Автор/Тривалість
 * rows are authoritative, the cover lives in `data-src` (no og:image), and the
 * playlist URL is inline in the page via `Playerjs({…file:"…pl.txt"})`.
 */
class SluhayAdapterTest {

    // The T1 capture, trimmed to the decision-rich parts. The <script src> for
    // playerjs is separate from the init call (as on the live page).
    private val bookPage = """
        <html><head>
        <meta property="og:title" content="Трохи ненависті - Джо Аберкромбі » Слухай безкоштовні АудіоКниги онлайн українською мовою" />
        <meta property="og:url" content="https://sluhay.com/svitova-literatura/6150-dzho-aberkrombi-trohi-nenavisti.html" />
        <meta property="og:description" content="Над Адуа зависочіли промислові труби, тож світ закипає, бо зароджується нова ера." />
        </head><body>
        <h1>Трохи ненависті - Джо Аберкромбі</h1>
        <ul class="pmovie__list">
        <li><span>Назва</span> <span>Трохи ненависті</span></li>
        <li><span>Автор</span> <span>Джо Аберкромбі</span></li>
        <li><span>Тривалість</span> <span>16:41:11</span></li>
        </ul>
        <img data-src="/uploads/posts/books/6150/dzho-aberkrombi-trohi-nenavisti.webp" src="/uploads/posts/books/6150/dzho-aberkrombi-trohi-nenavisti.webp" alt="Трохи ненависті - Джо Аберкромбі" class="lazy-loaded">
        <script src="/engine/modules/playerjs.js"></script>
        <script>
        Playerjs({id:"playerjs1",file:"https://9giiu0g54k8c.redirectto.cc/s05/2/6/5/4/4/26544.pl.txt"});
        </script>
        </body></html>
    """.trimIndent()

    // Real playlist JSON shape from the spike: per track `title` (the file
    // name) + `file` (the track mp3 on the shared redirectto.cc CDN).
    private val playlistJson = """
        [{"title":"Трохи ненависті 01.mp3","file":"https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3"},
         {"title":"Трохи ненависті 02.mp3","file":"https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-1.mp3"},
         {"title":"Трохи ненависті 03.mp3","file":"https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-2.mp3"}]
    """.trimIndent()

    private val bookUrl = "https://sluhay.com/svitova-literatura/6150-dzho-aberkrombi-trohi-nenavisti.html"
    private val playlistUrl = "https://9giiu0g54k8c.redirectto.cc/s05/2/6/5/4/4/26544.pl.txt"

    @Test
    fun `book page parses metadata and ordered chapters from the inline playlist`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(bookUrl to bookPage, playlistUrl to playlistJson))
        val adapter = SluhayAdapter(fetcher)

        val detail = adapter.fetchBookPage(bookUrl)

        // The meta rows are authoritative over og:title's " - " split.
        assertEquals("Трохи ненависті", detail.title)
        assertEquals("Джо Аберкромбі", detail.author)
        // Measured negative finding (T1): no narrator anywhere on the page.
        assertEquals("", detail.narrator)
        // Relative data-src → absolute, no og:image exists.
        assertEquals("https://sluhay.com/uploads/posts/books/6150/dzho-aberkrombi-trohi-nenavisti.webp", detail.coverImageUrl)
        assertEquals(16 * 3600L + 41 * 60L + 11L, detail.totalDurationSeconds)
        assertEquals(3, detail.chapters.size)
        assertEquals("Трохи ненависті 01", detail.chapters[0].title)
        assertEquals("https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3", detail.chapters[0].streamUrl)
        assertEquals("https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-2.mp3", detail.chapters[2].streamUrl)
    }

    @Test
    fun `captured html without a playlist yields metadata but no chapters`() = runBlocking {
        val pageWithoutPlayer = bookPage.replace(
            "Playerjs({id:\"playerjs1\",file:\"https://9giiu0g54k8c.redirectto.cc/s05/2/6/5/4/4/26544.pl.txt\"});",
            "// no player"
        )
        val adapter = SluhayAdapter(FakeFetcher(emptyMap()))

        val detail = adapter.detailFromCapturedHtml(pageWithoutPlayer, bookUrl)

        assertEquals("Трохи ненависті", detail.title)
        assertEquals("Джо Аберкромбі", detail.author)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `og title fallback splits when meta rows are absent`() = runBlocking {
        val minimalPage = """
            <html><head>
            <meta property="og:title" content="Метаморфоза Землі - Кларк Ештон Сміт » 💙💛 Аудіокниги" />
            </head><body>
            <h1>Метаморфоза Землі - Кларк Ештон Сміт</h1>
            <img data-src="/uploads/books/6066/cover.webp">
            </body></html>
        """.trimIndent()

        val detail = SluhayAdapter(FakeFetcher(emptyMap()))
            .detailFromCapturedHtml(minimalPage, "https://sluhay.com/svitova-literatura/6066-x.html")

        assertEquals("Метаморфоза Землі", detail.title)
        assertEquals("Кларк Ештон Сміт", detail.author)
        assertEquals("https://sluhay.com/uploads/books/6066/cover.webp", detail.coverImageUrl)
        assertNull(detail.totalDurationSeconds)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `blank html never throws and stays absent`() = runBlocking {
        val detail = SluhayAdapter(FakeFetcher(emptyMap()))
            .detailFromCapturedHtml("", bookUrl)

        assertEquals("", detail.title)
        assertEquals("", detail.author)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `garbage playlist json yields no chapters`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                bookUrl to bookPage,
                playlistUrl to "not-json-at-all"
            )
        )
        val adapter = SluhayAdapter(fetcher)

        val detail = adapter.fetchBookPage(bookUrl)

        assertEquals("Трохи ненависті", detail.title)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `search and feed are WebView-bound - the adapter stays empty`() = runBlocking {
        val adapter = SluhayAdapter(FakeFetcher(emptyMap()))

        // Cloudflare: discovery is in-session (T3 browser surface, T4 row).
        assertTrue(adapter.search("Шевченко").isEmpty())
        assertTrue(adapter.fetchNew(limit = 10).isEmpty())
        assertEquals("sluhay", adapter.sourceId)
    }
}
