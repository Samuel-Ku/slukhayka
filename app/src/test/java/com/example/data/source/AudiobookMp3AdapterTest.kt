package com.example.data.source

import com.example.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-10 T3 AudiobookMp3Adapter. Markup mirrors real
 * audiobook-mp3.com/uk pages captured during the T1 spike (book #6163,
 * «Клуб боягузів» — playlist `26720.pl.txt` on the redirectto.cc CDN).
 */
class AudiobookMp3AdapterTest {

    private val bookPage = """
        <html><head>
        <meta property="og:title" content="Клуб боягузів">
        </head><body>
        <script src="/js/playerjs-ua.js?v=1.1"></script>
        <script>var player = new Playerjs({file:"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt"});</script>
        </body></html>
    """.trimIndent()

    private val playlistJson = """[{"title":"001.mp3","file":"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-0.mp3"},{"title":"002.mp3","file":"https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-1.mp3"}]"""

    private val homepage = """
        <html><body>
        <a href="/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv">Клуб боягузів</a>
        <a href="/uk-audio-1246-dzhek-london-zhaga-do-zhittja">Жага до життя</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `book page parses the playerjs JSON playlist into chapters`() = runBlocking {
        val adapter = AudiobookMp3Adapter(
            FakeFetcher(
                mapOf(
                    "https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv" to bookPage,
                    "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt" to playlistJson
                )
            )
        )

        val detail = adapter.fetchBookPage("https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv")

        assertEquals("Клуб боягузів", detail.title)
        assertEquals(2, detail.chapters.size)
        assertEquals("https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-0.mp3", detail.chapters[0].streamUrl)
        assertEquals("002.mp3", detail.chapters[1].title)
    }

    @Test
    fun `book page without a playlist yields no chapters`() = runBlocking {
        val adapter = AudiobookMp3Adapter(
            FakeFetcher(mapOf("https://audiobook-mp3.com/uk-audio-1-x" to "<html><body>nope</body></html>"))
        )

        assertTrue(adapter.fetchBookPage("https://audiobook-mp3.com/uk-audio-1-x").chapters.isEmpty())
    }

    @Test
    fun `new feed parses recent uk book links`() = runBlocking {
        val adapter = AudiobookMp3Adapter(FakeFetcher(mapOf("https://audiobook-mp3.com/uk" to homepage)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(2, books.size)
        assertEquals("https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv", books[0].url)
        assertEquals("audiobookmp3", books[0].sourceId)
        assertTrue(books[0].title.isNotBlank())
    }
}
