package com.example.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-14 T4 — fixture tests for the pure [WebViewHtmlParser] module. No
 * network, no Android: a captured 4read book-page DOM maps straight to
 * [SourceBookDetail], the same shape the server-fetch door produces. Same
 * style as the og-tag fixture tests (FourReadAdapterTest).
 */
class WebViewHtmlParserTest {

    // Real 4read book page with the full pmovie profile (same markup shape as
    // FourReadAdapterTest.fullBookPage) — the WebView door's captured DOM.
    private val fullBookPage = """
        <html><head>
        <meta property="og:title" content="Неостанній бій">
        <meta property="og:image" content="https://4read.org/uploads/posts/2026-06/medium/neostannij-bij.webp">
        </head><body>
        <script>var player = new Playerjs({file:"https://4read.org/m3u/7589.txt"});</script>
        <ul class="pmovie__list">
          <li><span>Жанр:</span> <a href="/svitova-literatura/">Світова література</a> / <a href="/pryhody/">Пригоди</a> / <a href="/fentezi/">Фентезі</a></li>
          <li><span>Автор:</span> <a href="/avtors/kostyantyn-shelest/">Костянтин Шелест</a></li>
          <li><span>Читає:</span> <a href="/chytaje/valerij-zavalko/">Валерій Завалко</a></li>
          <li><span>Триває:</span> 10:57:18</li>
          <li><span>Цикл:</span> <a href="https://4read.org/xfsearch/cikl/maksym-temnyj/">Максим Темний</a> (<span itemprop="volumeNumber">7</span>)</li>
        </ul>
        <div class="pmovie__rating-score">4.9</div>
        <section class="sect pmovie__related carou">
            <h2 class="sect__title sect__header"><span>Можливо,</span> Тебе зацікавить:</h2>
            <div class="sect__content grid-items">
                <div class="poster has-overlay grid-item d-flex fd-column">
                    <div class="poster__desc order-last">
                        <a href="https://4read.org/7611-vkradi-mene-zaraz.html" class="poster__link"><div class="poster__title line-clamp">Вкради мене... Зараз!</div></a>
                        <div class="poster__subtitle ws-nowrap">Сергій Оріанець</div>
                    </div>
                    <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                        <img src="/uploads/posts/2026-06/medium/vkrady-mene-zaraz.webp" loading="lazy" alt="Сергій Оріанець - ВКРАДИ МЕНЕ... ЗАРАЗ!">
                    </div>
                </div>
            </div>
        </section>
        </body></html>
    """.trimIndent()

    private val playlistJson = """[{"title":"Глава 1","file":"https://4read.org/uploads/audio/7589/01.mp3"}]"""

    @Test
    fun `captured page maps the enriched profile - rating genres series related`() {
        val parser = WebViewHtmlParser()

        val detail = parser.parse(fullBookPage, "https://4read.org/7589-neostannij-bij.html") {
            if (it == "https://4read.org/m3u/7589.txt") playlistJson else ""
        }

        assertEquals("Неостанній бій", detail.title)
        assertEquals("Костянтин Шелест", detail.author)
        assertEquals("Валерій Завалко", detail.narrator)
        assertEquals(39438L, detail.totalDurationSeconds)
        assertEquals(4.9, detail.rating)
        assertEquals(listOf("Пригоди", "Фентезі"), detail.genres)
        assertEquals("Максим Темний", detail.series?.name)
        assertEquals(7, detail.series?.position)
        assertEquals("https://4read.org/xfsearch/cikl/maksym-temnyj/", detail.series?.url)
        assertEquals("https://4read.org/uploads/posts/2026-06/medium/neostannij-bij.webp", detail.coverImageUrl)
        assertEquals(1, detail.related.size)
        assertEquals("Вкради мене... Зараз!", detail.related[0].title)
        assertEquals("https://4read.org/uploads/posts/2026-06/medium/vkrady-mene-zaraz.webp", detail.related[0].coverImageUrl)
    }

    @Test
    fun `captured page expands the inline playlist into chapters`() {
        val parser = WebViewHtmlParser()

        val detail = parser.parse(fullBookPage, "https://4read.org/7589-neostannij-bij.html") {
            if (it == "https://4read.org/m3u/7589.txt") playlistJson else ""
        }

        assertEquals(1, detail.chapters.size)
        assertEquals("https://4read.org/uploads/audio/7589/01.mp3", detail.chapters.single().streamUrl)
    }

    @Test
    fun `blank or unparseable page yields absent fields never fabricated`() {
        val parser = WebViewHtmlParser()

        val blank = parser.parse("", "https://4read.org/bare.html")
        assertTrue(blank.chapters.isEmpty())
        assertEquals("", blank.author)
        assertNull(blank.rating)
        assertNull(blank.series)

        val bare = parser.parse("<html><body>no profile here</body></html>", "https://4read.org/bare.html")
        assertTrue(bare.chapters.isEmpty())
        assertEquals("", bare.author)
        assertEquals("", bare.narrator)
        assertNull(bare.rating)
        assertTrue(bare.genres.isEmpty())
        assertNull(bare.series)
        assertTrue(bare.related.isEmpty())
        assertNull(bare.totalDurationSeconds)
    }

    @Test
    fun `playlist is resolved through the caller so the module stays pure`() {
        val parser = WebViewHtmlParser()
        var resolved: String? = null

        parser.parse(fullBookPage, "https://4read.org/7589-neostannij-bij.html") {
            resolved = it
            playlistJson
        }

        assertEquals("https://4read.org/m3u/7589.txt", resolved)
    }
}
