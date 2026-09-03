package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        <meta property="og:description" content="Максим Темний повертається. Його чекає найважчий бій — бій з власним минулим.">
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
        // Spec-15 T5: og:description is the book's own blurb.
        assertEquals("Максим Темний повертається. Його чекає найважчий бій — бій з власним минулим.", detail.description)
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
        assertEquals("", bare.description)
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

    @Test
    fun `full annotation comes from the itemprop container with tail junk stripped`() {
        // Live page shape (#265): og:description is DLE-truncated (~295 chars),
        // while the body's itemprop="description" carries the FULL blurb —
        // followed by «Теги# …», YouTube and donation junk inside the SAME
        // container. The parser must take the blurb and cut at «Теги».
        val page = fullBookPage.replace(
            "</body>",
            """
            <div class="pmovie__text full-text clearfix" itemprop="description">
                <p>Кажуть, після справи «Дому шовку» Шерлок Голмс довгі роки відмовлявся згадувати про неї. Таємниче прохання торговця мистецтвом приводить Голмса й доктора Ватсона до розслідування.</p>
                <p>Ентоні Горовіц майстерно відтворює атмосферу класичних пригод Шерлока Голмса.</p>
                Теги# Шерлок Голмс , доктор Ватсон , детектив
                Ютуб канал диктора Подякувати диктору: Приват: 5457082257277959 PayPal: example@mail.com
            </div>
            </body>"""
        )
        val parser = WebViewHtmlParser()

        val detail = parser.parse(page, "https://4read.org/7978-dim-shovku.html")

        val expected = "Кажуть, після справи «Дому шовку» Шерлок Голмс довгі роки відмовлявся згадувати про неї. " +
            "Таємниче прохання торговця мистецтвом приводить Голмса й доктора Ватсона до розслідування.\n" +
            "Ентоні Горовіц майстерно відтворює атмосферу класичних пригод Шерлока Голмса."
        assertEquals(expected, detail.description)
        // The tail junk never reaches the stored field.
        assertTrue(!detail.description.contains("Теги"))
        assertTrue(!detail.description.contains("PayPal"))
        assertTrue(detail.description.length > 200)
    }

    @Test
    fun `tail junk sharing the last paragraph is cut and still newline-joined`() {
        // Live variant (#265): DLE sometimes keeps the tags line INSIDE the
        // final blurb paragraph — the cut must leave the kept part joined
        // like any other paragraph, never glued to the previous one.
        val page = fullBookPage.replace(
            "</body>",
            """
            <div class="pmovie__text full-text clearfix" itemprop="description">
                <p>Перший абзац справжньої анотації.</p>
                <p>Другий абзац анотації. Теги# детектив, класика Ютуб канал диктора</p>
            </div>
            </body>"""
        )
        val parser = WebViewHtmlParser()

        val detail = parser.parse(page, "https://4read.org/7978-dim-shovku.html")

        assertEquals("Перший абзац справжньої анотації.\nДругий абзац анотації.", detail.description)
    }

    @Test
    fun `telegram promo sharing the last container paragraph never reaches the description`() {
        // Live page /7589-neostannij-bij.html (#267): the promo paragraph holds
        // «Телеграм канал автора t.me/…» BEFORE «Подякувати…» — cutting at the
        // first marker BY LIST ORDER left the whole promo line in the blurb.
        // The cut must land on the EARLIEST marker position («Телеграм канал»).
        val page = fullBookPage.replace(
            "</body>",
            """
            <div class="pmovie__text full-text clearfix" itemprop="description">
                <p>Це сьома частина дуже довгої історії…</p>
                <p>Ти вже маг. Ти можеш усе.<br>Але відповіді створюють лише нові запитання.<br>І той бій, що ти вважав останнім, лише новий початок.<br><br></p>
                <p>Телеграм канал автора <a href="https://4read.org/go.html" title="Телеграм автора">t.me/KShelest_books_UA</a><br><br>Подякувати диктору за озвучку:<br><b>Приват</b>: 4149499095167902. Підтримати на Patreon.</p>
            </div>
            </body>"""
        )
        val parser = WebViewHtmlParser()

        val detail = parser.parse(page, "https://4read.org/7589-neostannij-bij.html")

        assertEquals(
            "Це сьома частина дуже довгої історії…\n" +
                "Ти вже маг. Ти можеш усе. Але відповіді створюють лише нові запитання. " +
                "І той бій, що ти вважав останнім, лише новий початок.",
            detail.description
        )
        assertTrue(!detail.description.contains("Телеграм"))
        assertTrue(!detail.description.contains("t.me"))
        assertTrue(!detail.description.contains("Подякувати"))
        assertTrue(!detail.description.contains("Patreon"))
    }

    @Test
    fun `paragraphs outside the container never reach the description even without markers`() {
        // Live pages carry user comments and the series list right after (and
        // on 7589 even inside, past the promo) the container. A page whose
        // annotation has NO marker paragraph must still yield exactly the
        // container's own paragraphs — including through a nested div.
        val page = fullBookPage.replace(
            "</body>",
            """
            <div class="pmovie__text full-text clearfix" itemprop="description">
                <p>Чистий перший абзац анотації.</p>
                <div class="quote"><p>Вкладений цитатний блок теж всередині.</p></div>
                <p>Чистий останній абзац анотації.</p>
            </div>
            <div class="comments">
                <p>Коментар відвідувача один.</p>
                <p>Коментар відвідувача два.</p>
            </div>
            <h2>Всі книги серії:</h2>
            <p>1. Книга перша 2. Книга друга</p>
            </body>"""
        )
        val parser = WebViewHtmlParser()

        val detail = parser.parse(page, "https://4read.org/7589-neostannij-bij.html")

        assertEquals(
            "Чистий перший абзац анотації.\n" +
                "Вкладений цитатний блок теж всередині.\n" +
                "Чистий останній абзац анотації.",
            detail.description
        )
        assertTrue(!detail.description.contains("Коментар"))
        assertTrue(!detail.description.contains("Книга перша"))
    }

    @Test
    fun `an empty itemprop container falls back to og description`() {
        val page = fullBookPage.replace(
            "</body>",
            """<div class="pmovie__text full-text clearfix" itemprop="description"></div></body>"""
        )
        val parser = WebViewHtmlParser()

        val detail = parser.parse(page, "https://4read.org/7589-neostannij-bij.html")

        assertEquals("Максим Темний повертається. Його чекає найважчий бій — бій з власним минулим.", detail.description)
    }

    @Test
    fun `cyrillic chapter paths stay percent-encoded - regression from device session`() {
        // Found on-device in spec-14 T6: a chapter file with a Cyrillic name
        // (e.g. 1984) 403'd because encodeUrl let Latin-1 re-mappings of
        // bytes >= 0x80 through isLetterOrDigit, storing `Ð%94...` instead of
        // `%D0%94...`. The correctly encoded URL is proven to serve 206.
        val parser = WebViewHtmlParser()
        // Real chapter filename captured from the device DB during the session
        // (the exact file that 403'd before the fix).
        val cyrillicPlaylist = """[{"title":"Розділ 1","file":"https://reasd.org/4984/01. Джордж Орвелл 1984 частина 1 розділ 1.mp3"}]"""

        val detail = parser.parse(fullBookPage, "https://4read.org/7589-neostannij-bij.html") {
            if (it == "https://4read.org/m3u/7589.txt") cyrillicPlaylist else ""
        }

        val url = detail.chapters.single().streamUrl
        assertTrue("raw Cyrillic must not survive: $url", !url.contains('Д') && !url.contains('Ð'))
        assertEquals(
            "https://reasd.org/4984/01.%20%D0%94%D0%B6%D0%BE%D1%80%D0%B4%D0%B6%20%D0%9E%D1%80%D0%B2%D0%B5%D0%BB%D0%BB%201984%20%D1%87%D0%B0%D1%81%D1%82%D0%B8%D0%BD%D0%B0%201%20%D1%80%D0%BE%D0%B7%D0%B4%D1%96%D0%BB%201.mp3",
            url
        )
    }


    /** #265 — live DLE body annotation with curated tail junk. */
    @Test
    fun `full annotation comes from itemprop container without tail junk`() {
        val parser = WebViewHtmlParser()
        val html = """
            <meta property="og:description" content="Короткий блурб 295">
            <div class="pmovie__text full-text clearfix" itemprop="description">
            <p>Перший абзац анотації про книгу.</p>
            <p>Другий абзац з продовженням історії.</p>
            <p>Теги# фентезі, пригоди</p>
            <p>Ютуб канал диктора Подякувати PayPal: x</p>
            </div>
        """.trimIndent()
        val detail = parser.parse(html, "https://4read.org/x.html") { "" }
        val d = detail.description
        assertTrue(d.contains("Перший абзац"))
        assertTrue(d.contains("Другий абзац"))
        assertFalse(d.contains("Теги"))
        assertFalse(d.contains("PayPal"))
    }

    /** #265 — no body container keeps the og:description path. */
    @Test
    fun `page without itemprop falls back to og description`() {
        val parser = WebViewHtmlParser()
        val html = """<meta property="og:description" content="Лише мета-блурб">"""
        val detail = parser.parse(html, "https://4read.org/y.html") { "" }
        assertEquals("Лише мета-блурб", detail.description)
    }

    // -----------------------------------------------------------------
    // Spec 2026-08-26 — the YouTube-embed fallback: a page with NO direct
    // audio at all resolves its chapters FROM the embed. Device case:
    // «Звички невдах» (4read 4355) — one YouTube embed (`ozaZXk5Qcwc`),
    // zero playerjs audio.
    // -----------------------------------------------------------------

    private fun minimalPage(vararg body: String): String = """
        <html>
        <head><title>Звички невдах. Досить мислити як лузер! - АудіоКниги Українською</title></head>
        <body>
        <div class="pmovie"><pmovie>Автор: Стівен Адамс</pmovie><pmovie>Читає: Корисні книги</pmovie></div>
        ${body.joinToString("\n")}
        </body>
        </html>
    """.trimIndent()

    @Test
    fun `captured 4read stream decodes an HTML encoded query separator`() {
        val detail = WebViewHtmlParser().parse(
            minimalPage(
                """<script>var player = new Playerjs({file:"https://s1.reasd.org/7810/chapter.mp3?expires=1788211520&amp%3Bmd5=ZxR0ibXl3Y9XkiMLf44Ltw"});</script>"""
            ),
            "https://4read.org/7810-dzho-aberkrombi-trohi-nenavisti.html"
        )

        assertEquals(
            "https://s1.reasd.org/7810/chapter.mp3?expires=1788211520&md5=ZxR0ibXl3Y9XkiMLf44Ltw",
            detail.chapters.single().streamUrl
        )
    }

    @Test
    fun `captured page with an unresolved full playlist does not become a two track book`() {
        val playlistUrl = "https://4read.org/m33u2/7810-dzho-aberkrombi-trohi-nenavisti.m3u"
        val detail = WebViewHtmlParser().parse(
            minimalPage(
                """
                <script>
                  var preview = "https://s1.reasd.org/7810/01.mp3?expires=1788211520&amp%3Bmd5=preview";
                  new Playerjs({file:"$playlistUrl"});
                </script>
                """.trimIndent()
            ),
            "https://4read.org/7810-dzho-aberkrombi-trohi-nenavisti.html"
        ) { "" }

        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun `browser-session playlist is authoritative over transient player audio`() {
        val playlist = """
            https://s1.reasd.org/7810/01.mp3?expires=1&md5=one
            https://s1.reasd.org/7810/02.mp3?expires=1&md5=two
            https://s1.reasd.org/7810/03.mp3?expires=1&md5=three
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(playlist.toByteArray())
        val detail = WebViewHtmlParser().parse(
            minimalPage(
                """
                <script>new Playerjs({file:"https://4read.org/m33u2/7810.m3u"});</script>
                <audio src="https://s1.reasd.org/7810/01.mp3?expires=old&amp%3Bmd5=old"></audio>
                <i data-slukhayka-playlist="$encoded"></i>
                """.trimIndent()
            ),
            "https://4read.org/7810.html"
        ) { error("The captured playlist must avoid a second HTTP request") }

        assertEquals(
            listOf(
                "https://s1.reasd.org/7810/01.mp3?expires=1&md5=one",
                "https://s1.reasd.org/7810/02.mp3?expires=1&md5=two",
                "https://s1.reasd.org/7810/03.mp3?expires=1&md5=three"
            ),
            detail.chapters.map { it.streamUrl }
        )
    }

    @Test
    fun `full WebView playlist fixture imports every 4read chapter in order`() {
        // This is the exact shape appended by WebSourceBrowserScreen after its
        // same-session XHR. A long manifest is important here: a partial
        // capture of the currently buffered tracks must never define a
        // two-chapter edition (#443).
        val expectedTracks = (1..65).map { chapter ->
            "https://s1.reasd.org/7810/${chapter.toString().padStart(2, '0')}.mp3?expires=1&md5=chapter-$chapter"
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(
            expectedTracks.joinToString("\n").toByteArray()
        )
        val detail = WebViewHtmlParser().parse(
            minimalPage(
                """
                <script>new Playerjs({file:"https://4read.org/m33u2/7810.m3u"});</script>
                <audio src="https://s1.reasd.org/7810/01.mp3?expires=old&amp%3Bmd5=partial"></audio>
                <i data-slukhayka-playlist="$encoded"></i>
                """.trimIndent()
            ),
            "https://4read.org/7810.html"
        ) { error("The browser-captured manifest is the import authority") }

        assertEquals(expectedTracks, detail.chapters.map { it.streamUrl })
    }

    @Test
    fun `4read OpenGraph site suffix is not part of the book title`() {
        val detail = WebViewHtmlParser().parse(
            """
            <meta property="og:title" content="Трохи ненависті - АудіоКниги Українською">
            <script>new Playerjs({file:"https://s1.reasd.org/7810/01.mp3"});</script>
            """.trimIndent(),
            "https://4read.org/7810.html"
        )

        assertEquals("Трохи ненависті", detail.title)
    }

    @Test
    fun `a page without playerjs audio but with a youtube embed resolves chapters from the embed`() {
        val html = minimalPage(
            """<iframe data-src="https://www.youtube.com/embed/ozaZXk5Qcwc" allowfullscreen></iframe>"""
        )

        val detail = WebViewHtmlParser().parse(html, "https://4read.org/4355-adams-stiven-zvychky-nevdakh.html")

        assertEquals(1, detail.chapters.size)
        assertEquals(
            "the watch URL is the persisted track locator",
            "https://www.youtube.com/watch?v=ozaZXk5Qcwc",
            detail.chapters[0].streamUrl
        )
    }

    @Test
    fun `multiple embeds become chapters in document order without duplicates`() {
        val html = minimalPage(
            """<iframe data-src="https://www.youtube.com/embed/aaaaaaaaaaa" allowfullscreen></iframe>""",
            """<a href="https://youtu.be/bbbbbbbbbbb">Частина 2</a>""",
            """<iframe src="https://www.youtube-nocookie.com/embed/aaaaaaaaaaa"></iframe>"""
        )

        val detail = WebViewHtmlParser().parse(html, "https://4read.org/some-book.html")

        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb"),
            detail.chapters.map { it.streamUrl.substringAfter("v=") }
        )
    }

    @Test
    fun `a page with playerjs audio never gains youtube chapters`() {
        val html = minimalPage(
            """<div id="playerjs1"></div><script>var playerjs1 = new Playerjs({id:"playerjs1",file:"https://cdn.example.org/audio.mp3"});</script>""",
            """<iframe data-src="https://www.youtube.com/embed/ozaZXk5Qcwc" allowfullscreen></iframe>"""
        )

        val detail = WebViewHtmlParser().parse(html, "https://4read.org/with-audio.html")

        assertTrue(detail.chapters.isNotEmpty())
        assertTrue(
            "direct audio wins; the embed adds nothing",
            detail.chapters.none { it.streamUrl.contains("youtube.com") }
        )
    }

    @Test
    fun `a page with neither audio nor embeds stays chapterless`() {
        val detail = WebViewHtmlParser().parse(minimalPage("<p>Просто текст</p>"), "https://4read.org/empty.html")

        assertEquals(0, detail.chapters.size)
    }

    @Test
    fun `object-wrapped json playlist expands every file entry`() {
        val playlistUrl = "https://4read.org/m33u2/7589-neostannij-bij-kostjantin-shelest.m3u"
        val wrapped = """{"playlist":[{"title":"Глава 1","file":"https://s1.reasd.org/7589/01.mp3"},{"title":"Глава 2","file":"https://s1.reasd.org/7589/02.mp3"}]}"""
        val detail = WebViewHtmlParser().parse(
            minimalPage("""<script>Playerjs({id:"playerjs1",file:"$playlistUrl"});</script>"""),
            "https://4read.org/7589-neostannij-bij-kostjantin-shelest.html"
        ) { wrapped }

        assertEquals(
            listOf("https://s1.reasd.org/7589/01.mp3", "https://s1.reasd.org/7589/02.mp3"),
            detail.chapters.map { it.streamUrl }
        )
    }

    @Test
    fun `pretty-printed json playlist expands every file entry`() {
        val playlistUrl = "https://4read.org/m33u2/7589-neostannij-bij-kostjantin-shelest.m3u"
        val pretty = """
            [
              {
                "title" : "Глава 1",
                "file" : "https://s1.reasd.org/7589/01.mp3"
              },
              {
                "title" : "Глава 2",
                "file" : "https://s1.reasd.org/7589/02.mp3"
              }
            ]
        """.trimIndent()
        val detail = WebViewHtmlParser().parse(
            minimalPage("""<script>Playerjs({id:"playerjs1",file:"$playlistUrl"});</script>"""),
            "https://4read.org/7589-neostannij-bij-kostjantin-shelest.html"
        ) { pretty }

        assertEquals(
            listOf("https://s1.reasd.org/7589/01.mp3", "https://s1.reasd.org/7589/02.mp3"),
            detail.chapters.map { it.streamUrl }
        )
    }

    @Test
    fun `live september 2026 page shape maps profile and session playlist`() {
        // Trimmed verbatim excerpts from the live DOM of
        // https://4read.org/7589-neostannij-bij-kostjantin-shelest.html
        // (2026-09-03, after the site's «я людина» gate): the playlist moved
        // from `/m3u/<id>.txt` + `{v1}` to `/m33u2/<slug>.m3u`, while the
        // pmovie profile kept its shape (schema.org wrappers included).
        val liveManifest = """
            https://s1.reasd.org/7589/01.mp3?expires=1&md5=one
            https://s1.reasd.org/7589/02.mp3?expires=1&md5=two
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(liveManifest.toByteArray())
        val detail = WebViewHtmlParser().parse(
            """
            <html><head>
            <meta property="og:title" content="Неостанній бій - АудіоКниги Українською">
            <meta property="og:image" content="https://4read.org/uploads/posts/2026-05/4_ks_mt7.webp">
            </head><body>
            <div id="playerjs1"></div><script>Playerjs({id:"playerjs1",file:"https://4read.org/m33u2/7589-neostannij-bij-kostjantin-shelest.m3u"});</script>
            <div class="pmovie__rating-score js-color-rating r1">4.7</div>
            <ul class="pmovie__list">
              <li><span>Жанр:</span> <a href="https://4read.org/ukrayinska/">Українська література</a> / <a href="https://4read.org/modern/">Сучасна проза</a> / <a href="https://4read.org/prygody/">Пригоди</a> / <a href="https://4read.org/fentezi/">Фентезі</a></li>
              <li class="not-shown-ajax"> <span>Автор:</span> <span itemprop="author" itemscope itemtype="https://schema.org/Person"> <span itemprop="name"><a href="https://4read.org/xfsearch/avtor/a/">Костянтин Шелест</a></span> </span> </li>
              <li> <span>Читає:</span> <span itemprop="readBy" itemscope itemtype="https://schema.org/Person"> <span itemprop="name"><a href="https://4read.org/xfsearch/chitaet/b/">Валерій Завалко</a></span> </span> </li>
              <li><span>Триває:</span> 23:02:02</li>
              <meta itemprop="duration" content="23:02:02" />
              <li itemscope itemtype="https://schema.org/PublicationVolume"> <span>Цикл:</span> <span itemprop="name"><a href="https://4read.org/xfsearch/cikl/c/">Максим Темний</a></span> (<span class="bolder" itemprop="volumeNumber">7</span>) </li>
            </ul>
            <i data-slukhayka-playlist="$encoded"></i>
            </body></html>
            """.trimIndent(),
            "https://4read.org/7589-neostannij-bij-kostjantin-shelest.html"
        ) { error("The browser-captured manifest is the import authority") }

        assertEquals("Неостанній бій", detail.title)
        assertEquals("Костянтин Шелест", detail.author)
        assertEquals("Валерій Завалко", detail.narrator)
        assertEquals(23 * 3600L + 2 * 60L + 2L, detail.totalDurationSeconds)
        assertEquals(4.7, detail.rating!!, 0.001)
        assertEquals("Максим Темний", detail.series?.name)
        assertEquals(
            listOf(
                "https://s1.reasd.org/7589/01.mp3?expires=1&md5=one",
                "https://s1.reasd.org/7589/02.mp3?expires=1&md5=two"
            ),
            detail.chapters.map { it.streamUrl }
        )
    }
}
