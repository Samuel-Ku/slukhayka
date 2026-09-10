package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-47 T4 UkrainianaudiobooksAdapter (WebView-pattern
 * source). Markup mirrors the REAL site's pages captured via the Wayback
 * Machine (committed under `docs/wayfinder/research/fixtures/ukrainianaudiobooks/`:
 * book page 2024-02-23, homepage 2025-04-09) — the site is Cloudflare-gated
 * to plain fetches since (T1 verdict GATED), so the T4 on-device session
 * re-verifies this markup. The playlist JSON is the playerjs `.pl.txt`
 * standard the repo's other playerjs sources parse; the CDN itself is not
 * archived, so its shape is re-verified with the session too. No network.
 */
class UkrainianaudiobooksAdapterTest {

    // book-2639-boplan-gyyom-levasser-opis-ukrayini.html (trimmed): og:title
    // with the « » Site» suffix, the h1 «Аудіокнига "Назва - Автор"», the
    // short-list rows (Жанр/Автор/Читає/Час), the lazy data-src cover, the
    // real annotation in shortstory-text, and the inline Playerjs init.
    private val bookPage = """
        <html><head>
        <meta property="og:title" content="Опис України - Гійом Левассер де Боплан » 1-ша Бібліотека Аудіокниг Українською Мовою Безкоштовно">
        <meta property="og:image" content="https://ukrainianaudiobooks.com/uploads/posts/books/2639/boplan-gyyom-levasser-opis-ukrayini.jpg">
        </head><body>
        <h1 class="short-title fx-1">Аудіокнига "Опис України - Гійом Левассер де Боплан"</h1>
        <div class="fcols fx-row">
        <div class="fleft">
        <div class="fimg img-wide">
        <img data-src="/uploads/posts/books/2639/boplan-gyyom-levasser-opis-ukrayini.jpg" alt="Опис України - Гійом Левассер де Боплан">
        </div>
        </div>
        <div class="fright fx-1 fx-col fx-between">
        <ul class="short-list">
        <li><span class="fa fa-file-audio" style="font-size: 16px;"> Жанр:</span> <a href="https://ukrainianaudiobooks.com/zarubizhna-literatura/">📚 Зарубіжна література</a></li>
        <li><span class="fa fa-pencil" style="font-size: 16px;"> Автор:</span> <a href="https://ukrainianaudiobooks.com/tags/x/">Гійом Левассер де Боплан</a></li>
        <li><span class="fa fa-microphone" style="font-size: 16px;"> Читає: </span> <a href="https://ukrainianaudiobooks.com/xfsearch/performer/x/">Борис Лобода</a></li>
        <li><span class="fa fa-play" style="font-size: 16px;"> Час:</span> 04:34:04</li>
        </ul>
        </div>
        </div>
        <div class="ftext full-text cleasrfix">
        <div class="ltext icon-left">
        <h2>Анотація (короткий опис) до цієї аудіокниги "Опис України - Гійом Левассер де Боплан"</h2>
        </div>
        <div class="shortstory-text">
        В книзі відомого мандрівника, архітектора та картографа подано безцінні відомості про тогочасну Україну, що після розпаду Київської Русі потрапила до складу Королівства Польського.
        </div>
        </div>
        <div id="part1" class="ftext full-text cleasrfix">
        <script src="/engine/modules/playerjs/playerjs.js" type="text/javascript"></script><div id="playerjs1" style="width:100%"></div><script>var playerjs1 = new Playerjs({id:"playerjs1",file:"https://Xp4sTM90BVzr.frontroute.org/s05/1/5/4/9/8/15498.pl.txt"});</script>
        </div>
        </body></html>
    """.trimIndent()

    // The playerjs .pl.txt standard shape the site's own Playerjs init points
    // to (same format sluhay/sluhayknigi/audiobookmp3 parse): per track
    // `title` (the file name) + `file` (the track mp3 on the frontroute.org
    // CDN). Shape re-verified with the T4 device session (CDN not archived).
    private val playlistJson = """
        [{"title":"Описание Украины 01.mp3","file":"https://Xp4sTM90BVzr.frontroute.org/s05/1/5/4/9/8/track-0.mp3"},
         {"title":"Описание Украины 02.mp3","file":"https://Xp4sTM90BVzr.frontroute.org/s05/1/5/4/9/8/track-1.mp3"},
         {"title":"Описание Украины 03.mp3","file":"https://Xp4sTM90BVzr.frontroute.org/s05/1/5/4/9/8/track-2.mp3"}]
    """.trimIndent()

    private val bookUrl = "https://ukrainianaudiobooks.com/2639-boplan-gyyom-levasser-opis-ukrayini.html"
    private val playlistUrl = "https://Xp4sTM90BVzr.frontroute.org/s05/1/5/4/9/8/15498.pl.txt"

    // home.html (trimmed): the sect-content grid of two short-item cards —
    // title link «Назва - Автор», data-src cover, short-label genre, fa-play
    // duration, explicit Автор/Читає rows — plus the category nav links the
    // catalog walk uses.
    private val homePage = """
        <html><head>
        <title>Аудіокниги українською мовою слухати онлайн безкоштовно</title>
        </head><body>
        <div class="sect-content"><div class="col-6 col-sm-4 col-md-3 col-xl-2"><div class="short-item">
            <div class="short-cols fx-row">
                <a class="short-img img-fit" href="https://ukrainianaudiobooks.com/4874-birchak-volodymyr-khytryi-panko.html">
                    <img data-src="/uploads/posts/books/no-cover.jpg" alt="Хитрий Панько - Володимир Бірчак">
                    <div class="short-label">📚 Українська література</div>
                </a>
                <div class="short-desc fx-1 fx-col fx-between">
                    <div class="short-header fx-row fx-middle">
                        <a class="short-title fx-1" href="https://ukrainianaudiobooks.com/4874-birchak-volodymyr-khytryi-panko.html">Хитрий Панько - Володимир Бірчак</a>
                    </div>
                    <div class="short-meta-item"><span class="fa fa-play"></span> 00:13:47</div>
                    <div class="short-meta fx-row fx-middle icon-left">
                        <div class="short-meta-item"><span class="fa fa-pencil">&#32;Автор:</span><a href="https://ukrainianaudiobooks.com/xfsearch/author/x/">Володимир Бірчак</a></div>
                        <div class="short-meta-item fx-1"><span class="fa fa-microphone"></span><a href="https://ukrainianaudiobooks.com/xfsearch/performer/x/">Валентина Семенова</a></div>
                    </div>
                </div>
            </div>
        </div><div class="short-item">
            <div class="short-cols fx-row">
                <a class="short-img img-fit" href="https://ukrainianaudiobooks.com/4873-shekli-robert-zhertva-z-kosmosu.html">
                    <img data-src="/uploads/posts/books/4873/shekli-robert-zhertva-z-kosmosu.webp" alt="Жертва з космосу - Роберт Шеклі">
                    <div class="short-label">📚 Світова література</div>
                </a>
                <div class="short-desc fx-1 fx-col fx-between">
                    <div class="short-header fx-row fx-middle">
                        <a class="short-title fx-1" href="https://ukrainianaudiobooks.com/4873-shekli-robert-zhertva-z-kosmosu.html">Жертва з космосу - Роберт Шеклі</a>
                    </div>
                    <div class="short-meta-item"><span class="fa fa-play"></span> 00:44:47</div>
                    <div class="short-meta fx-row fx-middle icon-left">
                        <div class="short-meta-item"><span class="fa fa-pencil">&#32;Автор:</span><a href="https://ukrainianaudiobooks.com/xfsearch/author/x/">Роберт Шеклі</a></div>
                        <div class="short-meta-item fx-1"><span class="fa fa-microphone"></span><a href="https://ukrainianaudiobooks.com/xfsearch/performer/x/">Аудіо Бібліотека</a></div>
                    </div>
                </div>
            </div>
        </div></div>
        <a href="https://ukrainianaudiobooks.com/roman/">Романи</a>
        <a href="https://ukrainianaudiobooks.com/fantastika/">Фантастика</a>
        </body></html>
    """.trimIndent()

    // A category page reuses the same short-item template (verified on the
    // live /roman/ capture): two distinct slugs so the walk is observable.
    private val categoryPage = """
        <html><body>
        <div class="sect-content"><div class="short-item">
            <div class="short-header fx-row fx-middle">
                <a class="short-title fx-1" href="https://ukrainianaudiobooks.com/4901-roman-knyha-a.html">Книга А - Автор А</a>
            </div>
        </div><div class="short-item">
            <div class="short-header fx-row fx-middle">
                <a class="short-title fx-1" href="https://ukrainianaudiobooks.com/4902-roman-knyha-b.html">Книга Б - Автор Б</a>
            </div>
        </div></div>
        </body></html>
    """.trimIndent()

    private fun adapterWithCookies(cookies: String, fetcher: FakeFetcher = FakeFetcher(emptyMap())) =
        UkrainianaudiobooksAdapter(fetcher, cookieProvider = FakeSourceCookieProvider(mapOf("ukrainianaudiobooks.com" to cookies)))

    private val homeUrl = "https://ukrainianaudiobooks.com/"

    @Test
    fun `book page parses metadata and ordered chapters from the inline playlist`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(bookUrl to bookPage, playlistUrl to playlistJson))
        val adapter = UkrainianaudiobooksAdapter(fetcher)

        val detail = adapter.fetchBookPage(bookUrl)

        // The h1 «Аудіокнига "…"» wrapper is stripped; the Автор row is
        // authoritative; Читає names the narrator — the site's own claim.
        assertEquals("Опис України", detail.title)
        assertEquals("Гійом Левассер де Боплан", detail.author)
        assertEquals("Борис Лобода", detail.narrator)
        assertEquals(listOf("📚 Зарубіжна література"), detail.genres)
        assertEquals(4 * 3600L + 34 * 60L + 4L, detail.totalDurationSeconds)
        // The annotation block is the real description.
        assertTrue(detail.description.startsWith("В книзі відомого мандрівника"))
        // The lazy data-src cover is made absolute.
        assertEquals(
            "https://ukrainianaudiobooks.com/uploads/posts/books/2639/boplan-gyyom-levasser-opis-ukrayini.jpg",
            detail.coverImageUrl
        )
        assertEquals(3, detail.chapters.size)
        assertEquals("Описание Украины 01", detail.chapters[0].title)
        assertEquals("https://Xp4sTM90BVzr.frontroute.org/s05/1/5/4/9/8/track-0.mp3", detail.chapters[0].streamUrl)
        assertEquals("https://Xp4sTM90BVzr.frontroute.org/s05/1/5/4/9/8/track-2.mp3", detail.chapters[2].streamUrl)
    }

    @Test
    fun `captured html without a playlist yields metadata but no chapters`() = runBlocking {
        val pageWithoutPlayer = bookPage.replace(
            """<script>var playerjs1 = new Playerjs({id:"playerjs1",file:"https://Xp4sTM90BVzr.frontroute.org/s05/1/5/4/9/8/15498.pl.txt"});</script>""",
            "<!-- no player -->"
        )
        // ADR-0006: the door works through the SourceAdapter INTERFACE under
        // the ONE captured-page name.
        val adapter: SourceAdapter = UkrainianaudiobooksAdapter(FakeFetcher(emptyMap()))

        val detail = adapter.parseCapturedPage(pageWithoutPlayer, bookUrl)

        assertNotNull(detail)
        assertEquals("Опис України", detail!!.title)
        assertEquals("Гійом Левассер де Боплан", detail.author)
        assertEquals("Борис Лобода", detail.narrator)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `og title fallback splits when the h1 and rows are absent`() = runBlocking {
        val minimalPage = """
            <html><head>
            <meta property="og:title" content="Метаморфоза Землі - Кларк Ештон Сміт » Аудіокниги українською мовою">
            <meta property="og:image" content="https://ukrainianaudiobooks.com/uploads/books/6066/cover.webp">
            </head><body>
            <img data-src="/uploads/books/6066/cover.webp">
            </body></html>
        """.trimIndent()

        val detail = UkrainianaudiobooksAdapter(FakeFetcher(emptyMap()))
            .parseCapturedPage(minimalPage, "https://ukrainianaudiobooks.com/6066-x.html")

        assertEquals("Метаморфоза Землі", detail.title)
        assertEquals("Кларк Ештон Сміт", detail.author)
        assertEquals("", detail.narrator)
        assertEquals("https://ukrainianaudiobooks.com/uploads/books/6066/cover.webp", detail.coverImageUrl)
        assertNull(detail.totalDurationSeconds)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `blank html never throws and stays absent`() = runBlocking {
        val detail = UkrainianaudiobooksAdapter(FakeFetcher(emptyMap()))
            .parseCapturedPage("", bookUrl)

        assertNotNull(detail)
        assertEquals("", detail!!.title)
        assertEquals("", detail.author)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `the cloudflare challenge page yields no playable chapters`() = runBlocking {
        // The ONE committed T1 capture (cloudflare-challenge.html): the
        // challenge interstitial a sessionless fetch would hand the parser.
        // ADR-0006/0014 — nothing playable, honestly; the metadata stays absent.
        val challenge = javaClass.classLoader?.getResourceAsStream(
            "/fixtures/ukrainianaudiobooks-cloudflare-challenge.html"
        )?.bufferedReader()?.use { it.readText() }.orEmpty()
        val adapter = UkrainianaudiobooksAdapter(FakeFetcher(emptyMap()))

        val detail = adapter.parseCapturedPage(challenge, bookUrl)

        assertNotNull(detail)
        assertEquals("", detail!!.title)
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
        val adapter = UkrainianaudiobooksAdapter(fetcher)

        val detail = adapter.fetchBookPage(bookUrl)

        assertEquals("Опис України", detail.title)
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `search and feed are WebView-bound - no live session means no feed`() = runBlocking {
        val adapter = UkrainianaudiobooksAdapter(FakeFetcher(emptyMap()))

        // Cloudflare: search is in-session (browser surface); the feed needs
        // the live session cookies, so without them it stays empty (the
        // repository surfaces the stale-session CTA then).
        assertTrue(adapter.search("Шевченко").isEmpty())
        assertTrue(adapter.fetchNew(limit = 10).isEmpty())
        assertEquals("ukrainianaudiobooks", adapter.sourceId)
        assertEquals("uk", adapter.contentLanguage)
        assertTrue(adapter.sessionBound)
    }

    @Test
    fun `homepage short-item cards parse into native feed books`() = runBlocking {
        val adapter = adapterWithCookies("cf_clearance=abc", FakeFetcher(mapOf(homeUrl to homePage)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(2, books.size)
        // «Назва - Автор» splits on the LAST separator; the Автор row is
        // authoritative; cover from data-src is made absolute; genre and the
        // real narrator ride along; sourceId marks the badge.
        assertEquals("Хитрий Панько", books[0].title)
        assertEquals("Володимир Бірчак", books[0].author)
        assertEquals("Валентина Семенова", books[0].narrator)
        assertEquals("https://ukrainianaudiobooks.com/uploads/posts/books/no-cover.jpg", books[0].coverImageUrl)
        assertEquals("📚 Українська література", books[0].genre)
        assertEquals(13 * 60L + 47L, books[0].totalDurationSeconds)
        assertEquals("https://ukrainianaudiobooks.com/4874-birchak-volodymyr-khytryi-panko.html", books[0].url)
        assertEquals("ukrainianaudiobooks", books[0].sourceId)
        assertEquals("Жертва з космосу", books[1].title)
        assertEquals("Роберт Шеклі", books[1].author)
        assertEquals("Аудіо Бібліотека", books[1].narrator)
    }

    @Test
    fun `the feed sends the session cookies with the homepage request`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(homeUrl to homePage))
        val adapter = adapterWithCookies("cf_clearance=abc; __cf_bm=xyz", fetcher)

        adapter.fetchNew(limit = 10)

        assertEquals(1, fetcher.recordedHeaders.size)
        assertEquals(mapOf("Cookie" to "cf_clearance=abc; __cf_bm=xyz"), fetcher.recordedHeaders[0])
    }

    @Test
    fun `feed stays empty when the session fetch is blocked or stale`() = runBlocking {
        // Fresh-looking cookies but the fetch comes back empty (Cloudflare 403
        // behind the scenes) — nothing to parse, the CTA covers it.
        val adapter = adapterWithCookies("cf_clearance=stale", FakeFetcher(emptyMap()))

        assertTrue(adapter.fetchNew(limit = 10).isEmpty())
    }

    @Test
    fun `feed respects the limit`() = runBlocking {
        val adapter = adapterWithCookies("cf_clearance=abc", FakeFetcher(mapOf(homeUrl to homePage)))

        val books = adapter.fetchNew(limit = 1)

        assertEquals(1, books.size)
        assertEquals("Хитрий Панько", books[0].title)
    }

    @Test
    fun `blank homepage never throws and yields no books`() = runBlocking {
        val adapter = adapterWithCookies("cf_clearance=abc", FakeFetcher(emptyMap()))

        assertTrue(adapter.parseShortItems("", limit = 10).isEmpty())
    }

    @Test
    fun `catalogue enumeration walks category pages through the session and dedupes by url`() = runBlocking {
        val romanUrl = "https://ukrainianaudiobooks.com/roman/"
        val fantastikaUrl = "https://ukrainianaudiobooks.com/fantastika/"
        val fetcher = FakeFetcher(
            mapOf(homeUrl to homePage, romanUrl to categoryPage, fantastikaUrl to categoryPage)
        )
        val adapter = adapterWithCookies("cf_clearance=abc", fetcher)

        val books = adapter.fetchCatalog(limit = 10)

        // Home rows (2) + one category's rows (2) — the second category hits
        // the limit path after the first walk, url-deduped.
        assertEquals(4, books.size)
        assertEquals("Хитрий Панько", books[0].title)
        assertEquals("Книга А", books[2].title)
        assertTrue(books.map { it.url }.distinct().size == books.size)
    }

    @Test
    fun `catalogue stays empty without a live session`() = runBlocking {
        val adapter = UkrainianaudiobooksAdapter(FakeFetcher(mapOf(homeUrl to homePage)))

        // Cloudflare: without the session cookies there is nothing to crawl.
        assertTrue(adapter.fetchCatalog(limit = 10).isEmpty())
    }

    @Test
    fun `catalogue sends the session cookies on home and category fetches`() = runBlocking {
        val romanUrl = "https://ukrainianaudiobooks.com/roman/"
        val fantastikaUrl = "https://ukrainianaudiobooks.com/fantastika/"
        val fetcher = FakeFetcher(
            mapOf(homeUrl to homePage, romanUrl to categoryPage, fantastikaUrl to categoryPage)
        )
        val adapter = adapterWithCookies("cf_clearance=abc; __cf_bm=xyz", fetcher)

        adapter.fetchCatalog(limit = 10)

        assertEquals(3, fetcher.recordedHeaders.size)
        assertTrue(
            fetcher.recordedHeaders.all { it["Cookie"] == "cf_clearance=abc; __cf_bm=xyz" }
        )
    }

    @Test
    fun `book page fetch sends the session cookies when present`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(bookUrl to bookPage, playlistUrl to playlistJson))
        val adapter = adapterWithCookies("cf_clearance=abc", fetcher)

        adapter.fetchBookPage(bookUrl)

        // The page fetch carries the session (Cloudflare); the playlist fetch
        // is header-less (Referer only, sent by the fetcher itself).
        assertEquals(1, fetcher.recordedHeaders.size)
        assertEquals(mapOf("Cookie" to "cf_clearance=abc"), fetcher.recordedHeaders[0])
    }
}