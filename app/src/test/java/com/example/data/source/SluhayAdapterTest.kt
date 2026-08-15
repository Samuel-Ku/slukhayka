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

    // T4 fixtures: the homepage poster-row markup captured live in the T1
    // spike (`docs/wayfinder/research/fixtures/webview/sluhay-home.html`),
    // trimmed to two blocks. Each block: book href, the lazy-loaded cover in
    // `data-src` (two imgs — absolute /uploads/ then relative), a
    // «Назва - Автор» title and slash-separated genres.
    private val homePage = """
        <html><body>
        <a class="poster-item grid-item" href="https://sluhay.com/svitova-literatura/6177-zhan-kristof-granzhe-pasazhir.html">
            <div class="poster-item__img img-fit-cover img-responsive img-responsive--portrait">
                <img data-src="/uploads/posts/books/6177/zhan-kristof-granzhe-pasazhir.webp" src="/templates/audiobookspbn-final-light/images/no-img.png" alt="Пасажир - Жан-Крістоф Гранже (Ґранже)" class="owl-lazy">
                <img data-src="books/6177/zhan-kristof-granzhe-pasazhir.webp" src="/templates/audiobookspbn-final-light/images/no-img.png" alt="Пасажир - Жан-Крістоф Гранже (Ґранже)" class="owl-lazy">
            </div>
            <div class="poster-item__desc">
                <div class="poster-item__title">Пасажир - Жан-Крістоф Гранже (Ґранже)</div>
                <div class="poster-item__meta">Світова література / Детектив / Драмa / Сучасна проза / Роман</div>
            </div>
        </a>
        <a class="poster-item grid-item" href="https://sluhay.com/ukrayinska-literatura/6155-andrij-bachinskij-z-ejnshtejnom-u-rjukzaku.html">
            <div class="poster-item__img img-fit-cover img-responsive img-responsive--portrait">
                <img data-src="/uploads/posts/books/6155/andrij-bachinskij-z-ejnshtejnom-u-rjukzaku.webp" src="/templates/audiobookspbn-final-light/images/no-img.png" alt="З Ейнштейном у рюкзаку - Андрій Бачинський" class="owl-lazy">
                <img data-src="books/6155/andrij-bachinskij-z-ejnshtejnom-u-rjukzaku.webp" src="/templates/audiobookspbn-final-light/images/no-img.png" alt="З Ейнштейном у рюкзаку - Андрій Бачинський" class="owl-lazy">
            </div>
            <div class="poster-item__desc">
                <div class="poster-item__title">З Ейнштейном у рюкзаку - Андрій Бачинський</div>
                <div class="poster-item__meta">Українська література / Повісті й оповідання / Пригоди</div>
            </div>
        </a>
        </body></html>
    """.trimIndent()

    private fun adapterWithCookies(cookies: String, fetcher: FakeFetcher = FakeFetcher(emptyMap())) =
        SluhayAdapter(fetcher, cookieProvider = { cookies })

    private val homeUrl = "https://sluhay.com/"

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
        // Spec-15 T5: og:description is the book's own blurb.
        assertEquals("Над Адуа зависочіли промислові труби, тож світ закипає, бо зароджується нова ера.", detail.description)
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
    fun `search and feed are WebView-bound - no live session means no feed`() = runBlocking {
        val adapter = SluhayAdapter(FakeFetcher(emptyMap()))

        // Cloudflare: search is in-session (T3 browser surface); the T4 feed
        // needs the live session cookies, so without them it stays empty (the
        // repository surfaces the stale-session CTA then).
        assertTrue(adapter.search("Шевченко").isEmpty())
        assertTrue(adapter.fetchNew(limit = 10).isEmpty())
        assertEquals("sluhay", adapter.sourceId)
        assertTrue(adapter.sessionBound)
    }

    @Test
    fun `homepage poster rows parse into native feed books`() = runBlocking {
        val adapter = adapterWithCookies("cf_clearance=abc", FakeFetcher(mapOf(homeUrl to homePage)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(2, books.size)
        // «Назва - Автор» splits on the LAST separator; cover from data-src is
        // made absolute; genres ride along; sourceId marks the badge.
        assertEquals("Пасажир", books[0].title)
        assertEquals("Жан-Крістоф Гранже (Ґранже)", books[0].author)
        assertEquals("https://sluhay.com/uploads/posts/books/6177/zhan-kristof-granzhe-pasazhir.webp", books[0].coverImageUrl)
        assertEquals("Світова література / Детектив / Драмa / Сучасна проза / Роман", books[0].genre)
        assertEquals("https://sluhay.com/svitova-literatura/6177-zhan-kristof-granzhe-pasazhir.html", books[0].url)
        assertEquals("sluhay", books[0].sourceId)
        assertEquals("З Ейнштейном у рюкзаку", books[1].title)
        assertEquals("Андрій Бачинський", books[1].author)
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
        assertEquals("Пасажир", books[0].title)
    }

    @Test
    fun `blank homepage never throws and yields no books`() = runBlocking {
        val adapter = adapterWithCookies("cf_clearance=abc", FakeFetcher(emptyMap()))

        assertTrue(adapter.parsePosterRows("", limit = 10).isEmpty())
    }

    // Spec-15 T3 hydration fixtures: a category page reuses the homepage
    // poster-row markup (same DLE template), so the crawler walks a few
    // category sections derived from the poster book URLs' first path segment.
    private val categoryPage = """
        <html><body>
        <a class="poster-item grid-item" href="https://sluhay.com/svitova-literatura/6201-entoni-gorovic-dim-shovku.html">
            <div class="poster-item__desc">
                <div class="poster-item__title">Дім шовку - Ентоні Горовіц</div>
            </div>
        </a>
        <a class="poster-item grid-item" href="https://sluhay.com/svitova-literatura/6107-andre-norton-chaklunskij-svit.html">
            <div class="poster-item__desc">
                <div class="poster-item__title">Чаклунський світ - Андре Нортон</div>
            </div>
        </a>
        </body></html>
    """.trimIndent()

    @Test
    fun `catalogue enumeration walks category pages through the session and dedupes by url`() = runBlocking {
        val categoryUrl = "https://sluhay.com/svitova-literatura/"
        val fetcher = FakeFetcher(mapOf(homeUrl to homePage, categoryUrl to categoryPage))
        val adapter = adapterWithCookies("cf_clearance=abc", fetcher)

        val books = adapter.fetchCatalog(limit = 10)

        // Home rows (2) + category rows (2) — no dedupe collision in this
        // fixture, but the union is capped by the limit and url-deduped.
        assertEquals(4, books.size)
        assertEquals("Пасажир", books[0].title)
        assertEquals("Дім шовку", books[2].title)
        assertTrue(books.map { it.url }.distinct().size == books.size)
    }

    @Test
    fun `catalogue stays empty without a live session`() = runBlocking {
        val adapter = SluhayAdapter(FakeFetcher(mapOf(homeUrl to homePage)))

        // Cloudflare: without the session cookies there is nothing to crawl.
        assertTrue(adapter.fetchCatalog(limit = 10).isEmpty())
    }

    @Test
    fun `catalogue sends the session cookies on home and category fetches`() = runBlocking {
        // The home fixture spans two categories (svitova-literatura,
        // ukrayinska-literatura), so the crawl fetches home + both category
        // pages — every request carrying the session cookies.
        val svitovaUrl = "https://sluhay.com/svitova-literatura/"
        val ukrayinskaUrl = "https://sluhay.com/ukrayinska-literatura/"
        val fetcher = FakeFetcher(mapOf(homeUrl to homePage, svitovaUrl to categoryPage, ukrayinskaUrl to categoryPage))
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
