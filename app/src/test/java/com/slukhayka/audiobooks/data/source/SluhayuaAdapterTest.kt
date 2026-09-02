package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-11 T2 SluhayuaAdapter. Markup mirrors real
 * sluhay.com.ua pages/JSON captured live during the T1 spike (trimmed into
 * `docs/wayfinder/research/fixtures/sluhayua/`).
 */
class SluhayuaAdapterTest {

    // Live `/find/allcards?search=Шевченко` response (search-shevchenko.json,
    // trimmed to 2 of 16 real matches — «кобзар» itself has no matches on the site).
    private val searchJson = """
        {"cards":[
          {"_id":4508492,"slug":"taras-shevchenko-Єretik","title":"Тарас Шевченко - Єретик","bookName":"Єретик","bookAuthor":["Тарас Шевченко"],"audioAuthor":["Євгеній Янович"],"kindSrc":"/uploads/1569063231.png","genre":["поема"],"timeLength":"00:02:46"},
          {"_id":2959974,"slug":"taras-shevchenko-nadj-djnіprovoju-sagoju","title":"Тарас Шевченко - Над Дніпровою сагою","bookName":"Над Дніпровою сагою","bookAuthor":["Тарас Шевченко"],"audioAuthor":["Максим Тимченко"],"kindSrc":"/uploads/1569063052.png","genre":["поезія"],"timeLength":"00:00:54"}
        ],"pageCount":1}
    """.trimIndent()

    // Live `sort=time&order=desc` response (new-sort-time.json, trimmed): the
    // newest card is a collection with no author (`[" "]`).
    private val newJson = """
        {"cards":[
          {"_id":9716855,"slug":"10-istorij-vid-psa-patrona-koli-ti","title":"10 історій від пса Патрона «Коли ти…»","bookName":"10 історій від пса Патрона «Коли ти…»","bookAuthor":[" "],"audioAuthor":["Григорій Решетник, Тімур Мірошниченко"],"kindSrc":"/uploads/1785180328.jpeg"},
          {"_id":7167611,"slug":"kostyantin-bakayevich-levenya","title":"Костянтин Бакаєвич - Левеня, яке навчилося ричати","bookName":"Левеня, яке навчилося ричати","bookAuthor":["Костянтин Бакаєвич"],"audioAuthor":["Сергій Лавренюк"],"kindSrc":"/uploads/levenya.jpeg"}
        ],"pageCount":18}
    """.trimIndent()

    // Book page with a 7-file inline playlist (book-multi-chapter.html, trimmed).
    private val multiChapterPage = """
        <html><head>
        <meta property="og:title" content="Григорій Квітка-Основяненко  - Сердешна Оксана. Слухай аудіокнигу онлайн" />
        <meta property="og:description" content="Аудіокнигу онлайн Сердешна Оксана, читає Діана Гончаренко. Цей твір був високо оцінений Т. Шевченком." />
        <meta property="og:image" content="https://sluhay.com.ua//uploads/kvitka2.png" />
        </head><body>
        <script>
        var playlist     = [["0",0],["1",1],["2",2],["3",3],["4",4],["5",5],["6",6]];
        </script>
        </body></html>
    """.trimIndent()

    // Single-file book page (book-single-file.html, trimmed).
    private val singleFilePage = """
        <html><head>
        <meta property="og:title" content="Ольга Кобилянська - Природа. Слухай аудіокнигу онлайн" />
        <meta property="og:description" content="Аудіокнигу онлайн Природа, читає Максим Тимченко. Новелу Природа Іван Франко зараховував до кращих." />
        </head><body>
        <script>
        var playlist     = [["0",0]];
        </script>
        </body></html>
    """.trimIndent()

    private val multiChapterUrl = "https://sluhay.com.ua/5931576:grigorij-kvitka-osnovjanenko-serdjeshna-oksana"

    @Test
    fun `search parses cards with real metadata and sends the XHR gate`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                "https://sluhay.com.ua/find/allcards?search=%D0%A8%D0%B5%D0%B2%D1%87%D0%B5%D0%BD%D0%BA%D0%BE&page=1" to searchJson
            )
        )
        val adapter = SluhayuaAdapter(fetcher)

        val books = adapter.search("Шевченко")

        assertEquals(2, books.size)
        assertEquals("Єретик", books[0].title)
        assertEquals("Тарас Шевченко", books[0].author)
        assertEquals("Євгеній Янович", books[0].narrator)
        assertEquals("https://sluhay.com.ua/4508492:taras-shevchenko-Єretik", books[0].url)
        assertEquals("https://sluhay.com.ua/uploads/1569063231.png", books[0].coverImageUrl)
        assertEquals("sluhayua", books[0].sourceId)
        // The allcards endpoint 404s without the header — the adapter must send it.
        assertTrue(fetcher.recordedHeaders.isNotEmpty())
        assertEquals("XMLHttpRequest", fetcher.recordedHeaders.first()["X-Requested-With"])
    }

    @Test
    fun `blank query returns nothing`() = runBlocking {
        val adapter = SluhayuaAdapter(FakeFetcher(emptyMap()))

        assertTrue(adapter.search("   ").isEmpty())
    }

    @Test
    fun `new feed returns newest first with limit and blank author for collections`() = runBlocking {
        val adapter = SluhayuaAdapter(
            FakeFetcher(
                mapOf("https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=1" to newJson)
            )
        )

        val books = adapter.fetchNew(limit = 1)

        assertEquals(1, books.size)
        // A collection with no author (`bookAuthor: [" "]`) never carries a
        // fake author — blank, so the Work-level merge key cannot form.
        assertEquals("10 історій від пса Патрона «Коли ти…»", books[0].title)
        assertEquals("", books[0].author)
    }

    @Test
    fun `fetchNewPage builds the page N url and keeps the XHR gate`() = runBlocking {
        // Spec #462 ID4 (#466): the feed cursor pulls successive pages via
        // allcards?page=N — the page parameter is part of the URL shape.
        val fetcher = FakeFetcher(
            mapOf("https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=3" to newJson)
        )
        val adapter = SluhayuaAdapter(fetcher)

        val books = adapter.fetchNewPage(3)

        assertEquals(2, books.size)
        assertEquals("10 історій від пса Патрона «Коли ти…»", books[0].title)
        assertTrue(fetcher.recordedHeaders.all { it["X-Requested-With"] == "XMLHttpRequest" })
    }

    @Test
    fun `new feed unescapes uXXXX cyrillic titles from the live json`() = runBlocking {
        // Live `sort=time&order=desc` response (2026-08-13, #88): the site
        // escapes EVERY non-ASCII char as `\uXXXX` — a literal title like
        // «Колобок» arrives as `\u041a\u043e\u043b\u043e\u0431\u043e\u043a`.
        val escapedNewJson = """
            {"cards":[
              {"_id":9991001,"slug":"kolobok","title":"\u041a\u043e\u043b\u043e\u0431\u043e\u043a","bookName":"\u041a\u043e\u043b\u043e\u0431\u043e\u043a","bookAuthor":["\u0423\u043a\u0440\u0430\u0457\u043d\u0441\u044c\u043a\u0430 \u043d\u0430\u0440\u043e\u0434\u043d\u0430 \u043a\u0430\u0437\u043a\u0430"],"audioAuthor":[" "],"kindSrc":"/uploads/kolobok.jpeg"}
            ],"pageCount":1}
        """.trimIndent()
        val adapter = SluhayuaAdapter(
            FakeFetcher(
                mapOf("https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=1" to escapedNewJson)
            )
        )

        val books = adapter.fetchNew(limit = 1)

        assertEquals(1, books.size)
        assertEquals("Колобок", books[0].title)
        assertEquals("Українська народна казка", books[0].author)
    }

    @Test
    fun `book page follows the inline playlist to per-file play urls`() = runBlocking {
        val fetcher = FakeFetcher(
            buildMap {
                put(multiChapterUrl, multiChapterPage)
                for (i in 0 until 7) {
                    put("https://sluhay.com.ua/play?bookId=5931576&fileId=$i", "https://mp3.sluhay.com.ua/Serdeshna/0${i + 1}.mp3")
                }
            }
        )
        val adapter = SluhayuaAdapter(fetcher)

        val detail = adapter.fetchBookPage(multiChapterUrl)

        assertEquals("Сердешна Оксана", detail.title)
        assertEquals("Григорій Квітка-Основяненко", detail.author)
        assertEquals("Діана Гончаренко", detail.narrator)
        assertEquals("https://sluhay.com.ua/uploads/kvitka2.png", detail.coverImageUrl)
        // Spec-15 T5 + #265: no body container here → og:description (the
        // real blurb) stays via pageDescription's fallback path.
        assertEquals("Аудіокнигу онлайн Сердешна Оксана, читає Діана Гончаренко. Цей твір був високо оцінений Т. Шевченком.", detail.description)
        assertEquals(7, detail.chapters.size)
        assertEquals("https://mp3.sluhay.com.ua/Serdeshna/03.mp3", detail.chapters[2].streamUrl)
        assertEquals("Глава 3", detail.chapters[2].title)
        // The /play calls go through the headerful path too.
        assertTrue(fetcher.recordedHeaders.all { it["X-Requested-With"] == "XMLHttpRequest" })
    }

    @Test
    fun `book page prefers the full body itemprop blurb over the og template`() = runBlocking {
        // Live shape (#265): the body's bookDescription[itemprop=description]
        // carries the CLEAN blurb (no «Аудіокнігу онлайн…» prefix) and a
        // trailing «Автор озвучки:» meta line that must be cut.
        val page = multiChapterPage.replace(
            "</body>",
            """
            <div class="bookDescription" itemprop="description">
                «Сердешна Оксана» — повість про перше кохання, яке випало на важкі часи.<br />
                <br />
                Другий абзац справжньої анотації.
                Автор озвучки: Діана Гончаренко
            </div>
            </body>"""
        )
        val adapter = SluhayuaAdapter(FakeFetcher(mapOf(multiChapterUrl to page)))

        val detail = adapter.fetchBookPage(multiChapterUrl)

        assertEquals(
            "«Сердешна Оксана» — повість про перше кохання, яке випало на важкі часи.\nДругий абзац справжньої анотації.",
            detail.description
        )
    }

    @Test
    fun `book page carries total duration from the Час запису row`() = runBlocking {
        // Live shape (spec-35 T6, #237): the meta row is
        // «<span class="rowName">… Час запису:</span>  01:44» — MM:SS here,
        // HH:MM:SS on longer books; both go through the shared parser.
        val mmss = multiChapterPage.replace(
            "<body>",
                """<body><div class="bookLayoutTitle">
                   <span class="rowName"><i class="fa fa-clock-o" aria-hidden="true"></i> Час запису:</span>  01:44
                   </div>"""
            )
        val hhmmss = multiChapterPage.replace(
            "<body>",
            """<body><div class="bookLayoutTitle">
               <span class="rowName"><i class="fa fa-clock-o" aria-hidden="true"></i> Час запису:</span>  1:02:03
               </div>"""
        )
        val adapter = SluhayuaAdapter(FakeFetcher(mapOf(multiChapterUrl to mmss, "https://sluhay.com.ua/x:y" to hhmmss)))

        assertEquals(104L, adapter.fetchBookPage(multiChapterUrl).totalDurationSeconds)
        assertEquals(3723L, adapter.fetchBookPage("https://sluhay.com.ua/x:y").totalDurationSeconds)
    }

    @Test
    fun `book page without the duration row keeps it absent`() = runBlocking {
        // Negative finding (spec-35 T6): pages without the «Час запису:» row
        // must keep totalDurationSeconds null — never fabricated.
        val adapter = SluhayuaAdapter(FakeFetcher(mapOf(multiChapterUrl to multiChapterPage)))

        assertNull(adapter.fetchBookPage(multiChapterUrl).totalDurationSeconds)
    }

    @Test
    fun `book page carries genres from the Жанр row`() = runBlocking {
        // Live shape (spec-35 T6): «Жанр:</span><div class="rowData"><a
        // class="filterLink" type="bookGenre" itemprop="genre">казка</a>…».
        val page = multiChapterPage.replace(
            "<body>",
            """<body><div class="bookLayoutTitle">
               <span class="rowName"><i class="fa fa-map-signs" aria-hidden="true"></i> Жанр:</span>
               <div class="rowData">
                 <a class="filterLink" type="bookGenre" href="/find/genre=казка" itemprop="genre">казка</a>
                 <a class="filterLink" type="bookGenre" href="/find/genre=поема" itemprop="genre">поема</a>
               </div>
               </div>"""
        )
        val adapter = SluhayuaAdapter(FakeFetcher(mapOf(multiChapterUrl to page)))

        assertEquals(listOf("казка", "поема"), adapter.fetchBookPage(multiChapterUrl).genres)
    }

    @Test
    fun `related books come from the card rolls excluding the book itself`() = runBlocking {
        // Live shape (spec-35 T6): each rail is a
        // «cardRollCategoryDescription» header followed by cards whose
        // «titlePreviewText» blurb precedes the /id:slug anchor. The SELF
        // book appears in the author rail too and must be excluded; an
        // anchor without the «Автор - Назва» separator keeps author empty.
        val page = multiChapterPage.replace(
            "</body>",
            """
            <div class="cardRollCategoryDescription bigText">
                Інші книги автора «Григорій Квітка-Основяненко»
                <span style="display:none">x</span>
            </div>
            <div>
              <span class="titlePreviewText">Превʼю самої книги в райлі.</span>
              <a href="$multiChapterUrl">Григорій Квітка-Основяненко - Сердешна Оксана</a>
            </div>
            <div>
              <span class="titlePreviewText">Інша книга того ж автора.</span>
              <a href="/3444041:grigorij-kvitka-insha-knyga">Григорій Квітка-Основяненко - Інша книга</a>
            </div>
            <div class="cardRollCategoryDescription bigText">
                Схожі книги
            </div>
            <div>
              <span class="titlePreviewText">Анфіса без роздільника автор - назва.</span>
              <a href="/1013262:anfіsa-zolotі-kosi">Анфіса – золоті коси</a>
            </div>
            </body>"""
        )
        val adapter = SluhayuaAdapter(FakeFetcher(mapOf(multiChapterUrl to page)))

        val related = adapter.fetchBookPage(multiChapterUrl).related

        assertEquals(2, related.size)
        assertEquals("Інша книга", related[0].title)
        assertEquals("Григорій Квітка-Основяненко", related[0].author)
        assertEquals("https://sluhay.com.ua/3444041:grigorij-kvitka-insha-knyga", related[0].url)
        assertEquals("Анфіса – золоті коси", related[1].title)
        assertEquals("", related[1].author)
    }

    @Test
    fun `pages without genre rows or rails keep genres and related empty`() = runBlocking {
        // Negative findings (spec-35 T6): absent «Жанр:» row → empty genres;
        // no cardRoll rails → empty related; a card BEFORE any rail header is
        // orphaned and must never become a related book.
        val page = multiChapterPage.replace(
            "</body>",
            """
            <div>
              <span class="titlePreviewText">Карта-сирота поза райлом.</span>
              <a href="/9999999:orphan-knyha">Сирота - Книга</a>
            </div>
            </body>"""
        )
        val adapter = SluhayuaAdapter(FakeFetcher(mapOf(multiChapterUrl to page)))

        val detail = adapter.fetchBookPage(multiChapterUrl)

        assertTrue(detail.genres.isEmpty())
        assertTrue(detail.related.isEmpty())
    }

    @Test
    fun `cards carry genre and duration from the allcards json`() = runBlocking {
        // Live allcards keys (spec-35 T6 inventory): «genre» is an array,
        // duration arrives as either «totalSeconds» (number, preferred) or
        // «timeLength» («01:00:00» / «01:44»). Rating and text exist in the
        // card but have no field in the SourceBook seam — documented, unused.
        val richJson = """
            {"cards":[
              {"_id":111,"slug":"a","bookName":"Книга А","bookAuthor":["Автор А"],"audioAuthor":["Диктор"],"kindSrc":"/uploads/a.png","genre":["поема","казка"],"timeLength":"01:44"},
              {"_id":222,"slug":"b","bookName":"Книга Б","bookAuthor":["Автор Б"],"audioAuthor":["Диктор"],"kindSrc":"/uploads/b.png","genre":[],"totalSeconds":3723}
            ],"pageCount":1}
        """.trimIndent()
        val adapter = SluhayuaAdapter(
            FakeFetcher(mapOf("https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=1" to richJson))
        )

        val books = adapter.fetchNew(limit = 2)

        assertEquals("поема, казка", books[0].genre)
        assertEquals(104L, books[0].totalDurationSeconds)
        assertEquals("", books[1].genre)
        assertEquals(3723L, books[1].totalDurationSeconds)
    }

    @Test
    fun `single-file book yields one chapter`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                "https://sluhay.com.ua/1965454:olga-kobilyanska-priroda" to singleFilePage,
                "https://sluhay.com.ua/play?bookId=1965454&fileId=0" to "https://mp3.sluhay.com.ua/Pryroda/Pryroda.mp3"
            )
        )
        val adapter = SluhayuaAdapter(fetcher)

        val detail = adapter.fetchBookPage("https://sluhay.com.ua/1965454:olga-kobilyanska-priroda")

        assertEquals("Природа", detail.title)
        assertEquals("Ольга Кобилянська", detail.author)
        assertEquals("Максим Тимченко", detail.narrator)
        assertEquals(1, detail.chapters.size)
        assertEquals("https://mp3.sluhay.com.ua/Pryroda/Pryroda.mp3", detail.chapters.single().streamUrl)
    }

    @Test
    fun `play loop stops on a 0 response`() = runBlocking {
        val twoFilePage = """
            <html><head>
            <meta property="og:title" content="Автор - Книга. Слухай аудіокнигу онлайн" />
            </head><body>
            <script>
            var playlist     = [["0",0],["1",1]];
            </script>
            </body></html>
        """.trimIndent()
        val fetcher = FakeFetcher(
            mapOf(
                "https://sluhay.com.ua/123:x" to twoFilePage,
                "https://sluhay.com.ua/play?bookId=123&fileId=0" to "https://mp3.sluhay.com.ua/x/01.mp3",
                "https://sluhay.com.ua/play?bookId=123&fileId=1" to "0"
            )
        )
        val adapter = SluhayuaAdapter(fetcher)

        val detail = adapter.fetchBookPage("https://sluhay.com.ua/123:x")

        assertEquals(1, detail.chapters.size)
    }

    @Test
    fun `cyrillic slug is url-encoded for the page fetch`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                "https://sluhay.com.ua/999999:%D0%9A%D0%BE%D0%B1%D0%B7%D0%B0%D1%80" to singleFilePage,
                "https://sluhay.com.ua/play?bookId=999999&fileId=0" to "https://mp3.sluhay.com.ua/kobzar/01.mp3"
            )
        )
        val adapter = SluhayuaAdapter(fetcher)

        val detail = adapter.fetchBookPage("https://sluhay.com.ua/999999:Кобзар")

        assertEquals(1, detail.chapters.size)
        assertEquals("Природа", detail.title)
    }

    @Test
    fun `unplayable page yields no chapters and keeps the slug metadata`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf("https://sluhay.com.ua/0:none" to "<html><body>nope</body></html>")
        )
        val adapter = SluhayuaAdapter(fetcher)

        val detail = adapter.fetchBookPage("https://sluhay.com.ua/0:none")

        assertTrue(detail.chapters.isEmpty())
    }
}
