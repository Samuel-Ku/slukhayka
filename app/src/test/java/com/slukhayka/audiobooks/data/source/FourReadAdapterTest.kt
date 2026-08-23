package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-10 T3 FourReadAdapter (parser seam). Markup
 * mirrors real 4read.org pages captured during the T1 spike.
 */
class FourReadAdapterTest {

    private val bookPage = """
        <html><head>
        <meta property="og:title" content="Неостанній бій">
        <meta property="og:image" content="https://4read.org/uploads/posts/2026-06/medium/neostannij-bij.webp">
        </head><body>
        <script>var player = new Playerjs({file:"https://4read.org/m3u/7589.txt"});</script>
        </body></html>
    """.trimIndent()

    private val playlistJson = """[{"title":"Глава 1","file":"https://4read.org/uploads/audio/7589/01.mp3"}]"""

    // Real search page: each hit is a .poster block with the Cyrillic title in
    // poster__title, the author in the first poster__subtitle and the duration
    // clock in the second (captured during this session's live fetch).
    private val searchPage = """
        <html><body>
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/5359-taras-shevchenko-kobzar.html" class="poster__link"><div class="poster__title line-clamp">Кобзар</div></a>
                <div class="poster__subtitle ws-nowrap">Тарас Шевченко</div>
                <div class="poster__subtitle"><span class="lcomm__link fal fa-clock" aria-hidden="true"></span><span class="js-duration" data-time="02:19:35"></span></div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2025-05/medium/shevchenko-taras-kobzar.webp" alt="Шевченко Тарас - Кобзар">
            </div>
        </div>
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7199-nechista-sila.html" class="poster__link"><div class="poster__title line-clamp">Нечиста сила</div></a>
                <div class="poster__subtitle ws-nowrap">Іван Андрусяк</div>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    private val homepage = """
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7611-vkradi-mene-zaraz.html" class="poster__link"><div class="poster__title line-clamp">Вкради мене... Зараз!</div></a>
                <div class="poster__subtitle ws-nowrap">Сергій Оріанець</div>
            </div>
        </div>
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7589-neostannij-bij.html" class="poster__link"><div class="poster__title line-clamp">Неостанній бій</div></a>
                <div class="poster__subtitle ws-nowrap">Костянтин Шелест</div>
            </div>
        </div>
    """.trimIndent()

    // Real book page with the full pmovie profile (spec-14 T1): Автор / Читає /
    // Жанр / Триває / Цикл entries, a pmovie__rating-score and a
    // "Можливо, Тебе зацікавить:" related section — markup shape captured
    // from live 4read pages during the spec-14 review.
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

    @Test
    fun `book page parses the enriched profile - rating genres series related`() = runBlocking {
        val adapter = FourReadAdapter(
            FakeFetcher(
                mapOf(
                    "https://4read.org/7589-neostannij-bij.html" to fullBookPage,
                    "https://4read.org/m3u/7589.txt" to playlistJson
                )
            )
        )

        val detail = adapter.fetchBookPage("https://4read.org/7589-neostannij-bij.html")

        // The real profile replaces the old "4read.org" / "4read Voice
        // Narrator" placeholders straight from the page.
        assertEquals("Костянтин Шелест", detail.author)
        assertEquals("Валерій Завалко", detail.narrator)
        assertEquals(39438L, detail.totalDurationSeconds)
        assertEquals(4.9, detail.rating)
        // The broad "Світова література" first genre is dropped — the same
        // rendering the repository historically stored.
        assertEquals(listOf("Пригоди", "Фентезі"), detail.genres)
        assertEquals("Максим Темний", detail.series?.name)
        assertEquals(7, detail.series?.position)
        assertEquals("https://4read.org/xfsearch/cikl/maksym-temnyj/", detail.series?.url)
        assertEquals(1, detail.related.size)
        assertEquals("Вкради мене... Зараз!", detail.related[0].title)
        assertEquals("Сергій Оріанець", detail.related[0].author)
        assertEquals("https://4read.org/7611-vkradi-mene-zaraz.html", detail.related[0].url)
        assertEquals("https://4read.org/uploads/posts/2026-06/medium/vkrady-mene-zaraz.webp", detail.related[0].coverImageUrl)
    }

    @Test
    fun `book page without profile markup yields absent fields never fabricated`() = runBlocking {
        val adapter = FourReadAdapter(
            FakeFetcher(mapOf("https://4read.org/bare.html" to "<html><body>no profile here</body></html>"))
        )

        val detail = adapter.fetchBookPage("https://4read.org/bare.html")

        assertTrue(detail.chapters.isEmpty())
        // Absent, not fabricated: no "4read.org" / "4read Voice Narrator"
        // placeholders, no invented rating/genres/series/related.
        assertEquals("", detail.author)
        assertEquals("", detail.narrator)
        assertNull(detail.rating)
        assertTrue(detail.genres.isEmpty())
        assertNull(detail.series)
        assertTrue(detail.related.isEmpty())
        assertNull(detail.totalDurationSeconds)
    }

    @Test
    fun `book page expands the playerjs playlist into chapters`() = runBlocking {
        val adapter = FourReadAdapter(
            FakeFetcher(
                mapOf(
                    "https://4read.org/7589-neostannij-bij.html" to bookPage,
                    "https://4read.org/m3u/7589.txt" to playlistJson
                )
            )
        )

        val detail = adapter.fetchBookPage("https://4read.org/7589-neostannij-bij.html")

        assertEquals("Неостанній бій", detail.title)
        assertEquals("https://4read.org/uploads/posts/2026-06/medium/neostannij-bij.webp", detail.coverImageUrl)
        assertEquals(1, detail.chapters.size)
        assertEquals("https://4read.org/uploads/audio/7589/01.mp3", detail.chapters.single().streamUrl)
    }

    @Test
    fun `book page without a playlist yields no chapters instead of fake audio`() = runBlocking {
        val adapter = FourReadAdapter(
            FakeFetcher(mapOf("https://4read.org/empty.html" to "<html><body>nope</body></html>"))
        )

        val detail = adapter.fetchBookPage("https://4read.org/empty.html")

        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `search parses poster blocks with real title author and cover`() = runBlocking {
        val adapter = FourReadAdapter(FakeFetcher(emptyMap(), fallback = searchPage))

        val books = adapter.search("Кобзар")

        assertEquals(2, books.size)
        assertEquals("https://4read.org/5359-taras-shevchenko-kobzar.html", books[0].url)
        assertEquals("Кобзар", books[0].title)
        // The real Cyrillic author replaces the old "4read.org" placeholder so
        // the same Work found on another source can merge.
        assertEquals("Тарас Шевченко", books[0].author)
        assertEquals("https://4read.org/uploads/posts/2025-05/medium/shevchenko-taras-kobzar.webp", books[0].coverImageUrl)
        assertEquals("4read", books[0].sourceId)
        assertEquals("Нечиста сила", books[1].title)
        assertEquals("Іван Андрусяк", books[1].author)
    }

    @Test
    fun `new feed parses homepage posters`() = runBlocking {
        val adapter = FourReadAdapter(FakeFetcher(mapOf("https://4read.org/" to homepage)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(2, books.size)
        assertEquals("Вкради мене... Зараз!", books[0].title)
        assertEquals("Сергій Оріанець", books[0].author)
        assertEquals("https://4read.org/7611-vkradi-mene-zaraz.html", books[0].url)
    }

    @Test
    fun `bookId is the 4read-slug scheme in exactly this one place`() {
        val adapter = FourReadAdapter(FakeFetcher(emptyMap()))

        // The "4read-slug" id scheme — no import door may derive ids itself.
        assertEquals("4read-7589-neostannij-bij", adapter.bookId("https://4read.org/7589-neostannij-bij.html"))
        assertEquals("4read-7611-vkradi-mene-zaraz", adapter.bookId("https://4read.org/7611-vkradi-mene-zaraz.html"))
    }

    @Test
    fun `captured page parses through the shared parser with adapter transport`() = runBlocking {
        // ADR-0006: the door works through the SourceAdapter INTERFACE — no
        // import door may downcast to the concrete FourReadAdapter.
        val adapter: SourceAdapter = FourReadAdapter(
            FakeFetcher(mapOf("https://4read.org/m3u/7589.txt" to playlistJson))
        )

        // The WebView door hands the adapter the captured DOM; playlist
        // content resolves through the adapter's own transport.
        val detail = adapter.parseCapturedPage(bookPage, "https://4read.org/7589-neostannij-bij.html")

        assertNotNull(detail)
        assertEquals("Неостанній бій", detail!!.title)
        assertEquals(1, detail.chapters.size)
        assertEquals("https://4read.org/uploads/audio/7589/01.mp3", detail.chapters.single().streamUrl)
    }

    @Test
    fun `the captured-page capability defaults to not-mine on non-WebView adapters`() = runBlocking {
        // ADR-0006: the interface carries the door with a default — a plain
        // (server-fetch) adapter simply does not support it, so the door
        // needs no cast and no per-source branching.
        val plain: SourceAdapter = object : SourceAdapter {
            override val sourceId = "plain"
            override suspend fun search(query: String): List<SourceBook> = emptyList()
            override suspend fun fetchBookPage(url: String): SourceBookDetail =
                SourceBookDetail(title = "", author = "", url = url, chapters = emptyList())
            override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        }

        assertNull(plain.parseCapturedPage("<html></html>", "https://plain.example/book.html"))
    }

    // ---------------------------------------------------------------------
    // Spec-40 #282 — коментарі відвідувачів 4read (parseComments). Markup
    // mirrors the live 4read DLE comment tree: page__comments-list container,
    // each comment's text in a dedicated comm-id-N div, replies nested inside
    // their parent li.
    // ---------------------------------------------------------------------

    // Real comment-tree shape (captured during this session's live fetch):
    // header/meta/footer noise around each text div, a nested reply, entities
    // and inline tags inside the texts.
    private val commentsPage = """
        <html><body>
        <div class="page__comments-title"></div>
        <div data-comms="3" class="page__comments-list" id="page__comments-list">
            <form method="post" name="dlemasscomments"><div id="dle-comments-list"></div></form>
            <ol class="comments-tree-list">
              <li id="comments-tree-item-47709" class="comments-tree-item">
                <div id='comment-id-47709'><div class="comment-item js-comm">
                  <div class="comment-item__header d-flex ai-center">
                    <div class="comment-item__author ws-nowrap js-comm-author"><span>Lisa</span></div>
                  </div>
                  <div class="comment-item__main full-text clearfix">
                    <div id='comm-id-47709'><p>Дякуємо за &quot;Неостанній бій&quot;! Чекаємо на продовження…</p></div>
                  </div>
                  <div class="comment-item__footer d-flex ai-center">
                    <div class="comment-item__date ws-nowrap"><span>У п'ятницю у 03:20</span></div>
                  </div>
                </div></div>
                <ol class="comments-tree-list">
                  <li id="comments-tree-item-47723" class="comments-tree-item">
                    <div id='comment-id-47723'><div class="comment-item js-comm">
                      <div class="comment-item__main full-text clearfix">
                        <div id='comm-id-47723'><p>Приєднуюсь: <b>міцного здоров'я</b> і нових творчих здобутків!</p></div>
                      </div>
                      <div class="signature">--------------------<br>Підпис</div>
                    </div></div>
                  </li>
                </ol>
              </li>
              <li id="comments-tree-item-47800" class="comments-tree-item">
                <div id='comment-id-47800'><div class="comment-item js-comm">
                  <div class="comment-item__main full-text clearfix">
                    <div id='comm-id-47800'>   </div>
                  </div>
                </div></div>
              </li>
            </ol>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `page comments are parsed as plain texts in page order`() = runBlocking {
        val adapter = FourReadAdapter(FakeFetcher(emptyMap()))

        val comments = adapter.parseComments(commentsPage)

        // Page order preserved (parent before its nested reply); tags and
        // entities decoded; blanks dropped.
        assertEquals(2, comments.size)
        assertEquals("""Дякуємо за "Неостанній бій"! Чекаємо на продовження…""", comments[0])
        assertEquals("Приєднуюсь: міцного здоров'я і нових творчих здобутків!", comments[1])
    }

    @Test
    fun `a page without comments yields empty instead of fabricated texts`() = runBlocking {
        val adapter = FourReadAdapter(FakeFetcher(emptyMap()))

        assertTrue(adapter.parseComments("<html><body><p>no comments here</p></body></html>").isEmpty())
        assertTrue(adapter.parseComments("").isEmpty())
    }

    @Test
    fun `comment output stays bounded - one hundred texts of five hundred chars`() = runBlocking {
        val adapter = FourReadAdapter(FakeFetcher(emptyMap()))
        val longText = "Слово ".repeat(200) // 1200 chars — must truncate to 500
        val many = (1..105).joinToString("\n") { i ->
            """<div id='comm-id-$i'><p>$longText</p></div>"""
        }
        val html = """<div id="page__comments-list">$many</div>"""

        val comments = adapter.parseComments(html)

        assertEquals(100, comments.size)
        assertEquals(500, comments[0].length)
        assertTrue(comments[99].isNotBlank())
    }

    @Test
    fun `the comments capability defaults to empty on adapters without proven comments`() = runBlocking {
        // Spec-40 #282: the interface carries the default — a source without
        // provable page comments (sound-books, sluhay, audiobookmp3, …)
        // inherits "none" for ANY input, costing nothing and changing no
        // parsing behavior of its own.
        val soundbooks: SourceAdapter = SoundBooksAdapter()

        assertTrue(soundbooks.parseComments(commentsPage).isEmpty())
    }
}
