package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.privacy.PacingParams
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Fixture tests for the spec-10 T3 SoundBooksAdapter. Markup mirrors real
 * sound-books.net pages captured during the T1 spike (book #4111, «Темна
 * матерія») and the spec-35 #237 field inventory (live fetch).
 */
class SoundBooksAdapterTest {

    private val bookPage = """
        <html><head>
        <meta property="og:title" content="Темна матерія">
        <meta property="og:description" content="Роман Блейка Крауча про квантову фізику, паралельні світи та ціну вибору.">
        <meta property="og:image" content="https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp">
        </head><body>
        <p>Автор: Блейк Крауч. Читає: Pik CAH4E3. Триває: 09:28:09</p>
        <script>
        PlayerLang     = {prev: 'Попередній'}
        var player = new Playerjs({file:"https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u"});
        </script>
        </body></html>
    """.trimIndent()

    private val m3u = """
        https://arch.sound-books.net/4111/Темна матерія-01.mp3
        https://arch.sound-books.net/4111/Темна матерія-02.mp3
    """.trimIndent()

    // Same shape as [bookPage] minus og:image — the no-cover case.
    private val bookPageWithoutCover = """
        <html><head>
        <meta property="og:title" content="Темна матерія">
        <meta property="og:description" content="Роман Блейка Крауча про квантову фізику, паралельні світи та ціну вибору.">
        </head><body>
        <p>Автор: Блейк Крауч. Читає: Pik CAH4E3. Триває: 09:28:09</p>
        <script>
        var player = new Playerjs({file:"https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u"});
        </script>
        </body></html>
    """.trimIndent()

    // og:image in relative form — resolved against the site origin.
    private val bookPageRelativeCover = """
        <html><head>
        <meta property="og:title" content="Сонячна машина">
        <meta property="og:image" content="/uploads/posts/2026-07/soniachna-mashyna.webp">
        </head><body>
        <p>Автор: Володимир Винниченко. Читає: Тест.</p>
        <script>
        var player = new Playerjs({file:"https://sound-books.net/uploads/public_files/2026-07/3001-soniachna-mashyna.m3u"});
        </script>
        </body></html>
    """.trimIndent()

    // Spec-35 T5 — live page shape with the profile fields inventory #237
    // verified: duration («Триває: HH:MM:SS» in <li>), genres («Жанр:» links
    // with category prefixes stripped), rating («Рейтинг: N»), cover (og:image),
    // narrator («Читає:») and a real blurb (og:description).
    private val bookPageFull = """
        <html><head>
        <meta property="og:title" content="Темна матерія">
        <meta property="og:description" content="Роман Блейка Крауча про квантову фізику, паралельні світи та ціну вибору.">
        <meta property="og:image" content="https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp">
        </head><body>
        <p>Автор: Блейк Крауч. Читає: Pik CAH4E3. Триває: 09:28:09.</p>
        <ul>
            <li><b>Триває:</b> <strong>09:28:09</strong></li>
            <li><b>Жанр:</b> <a href="https://sound-books.net/zarubizhna-literatura/">Аудіокниги Зарубіжна література</a> / <a href="https://sound-books.net/fantastyka/">Аудіокниги Фантастика</a></li>
            <li><b>Рейтинг:</b> 4 (1 голосів)</li>
        </ul>
        <script>
        PlayerLang     = {prev: 'Попередній'}
        var player = new Playerjs({file:"https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u"});
        </script>
        </body></html>
    """.trimIndent()

    // Same as [bookPageFull] minus duration/genres/rating — negative fixture.
    private val bookPageMinimal = """
        <html><head>
        <meta property="og:title" content="Темна матерія">
        <meta property="og:description" content="Роман Блейка Крауча про квантову фізику, паралельні світи та ціну вибору.">
        </head><body>
        <p>Автор: Блейк Крауч. Читає: Pik CAH4E3.</p>
        <script>
        var player = new Playerjs({file:"https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u"});
        </script>
        </body></html>
    """.trimIndent()

    // The homepage renders each entry twice: a bare-title cover tile and a
    // «Назва - Автор» tile — both pointing at the same url (real markup).
    private val homepage = """
        <html><body>
        <a class="short-img img-fit" href="https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html"><img data-src="/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp" alt="Темна матерія"></a>
        <a class="short-title" href="https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html">Темна матерія 15.07.26 970 2</a>
        <a class="short-title" href="https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html">Темна матерія - Блейк Крауч</a>
        <a class="short-title" href="https://sound-books.net/ukrainska-literatura/2850-statut-vnutrishnoi-sluzhby-zbroinykh-syl-ukrainy.html">Статут внутрішньої служби Збройних Сил України</a>
        </body></html>
    """.trimIndent()

    // Live card shape (spec-35 #237): listing cards in <div class="short-item">
    // carry duration («Триває: HH:MM:SS»), genre breadcrumb links and a
    // short-text blurb in short-meta/short-text blocks.
    private val homepageWithCardExtras = """
        <html><body>
        <div class="short-item">
            <div class="short-cols fx-row">
                <a class="short-img img-fit" href="https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html"><img data-src="/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp" alt="Темна матерія"></a>
                <div class="short-desc fx-1">
                    <a class="short-title" href="https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html">Темна матерія - Блейк Крауч</a>
                    <div class="short-text">«Чи задоволений ти своїм життям?» — це останні слова, які чує Джейсон Дессен.</div>
                </div>
            </div>
            <div class="short-meta fx-row fx-middle">
                <div class="short-meta-item"><span class="fal fa-folder"></span><a href="https://sound-books.net/zarubizhna-literatura/">Аудіокниги Зарубіжна література</a> / <a href="https://sound-books.net/fantastyka/">Аудіокниги Фантастика</a></div>
                <div class="short-meta-item fx-1"><b>Триває: 09:28:00</b></div>
            </div>
        </div>
        <div class="short-item">
            <div class="short-cols fx-row">
                <div class="short-desc fx-1">
                    <a class="short-title" href="https://sound-books.net/ukrainska-literatura/2850-statut.html">Статут внутрішньої служби Збройних Сил України</a>
                </div>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `book page follows the m3u playlist to direct mp3 chapters`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html" to bookPage,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to m3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html")

        assertEquals("Темна матерія", detail.title)
        assertEquals("Блейк Крауч", detail.author)
        assertEquals("Pik CAH4E3", detail.narrator)
        // Spec-15 T5: og:description is the book's own blurb.
        assertEquals("Роман Блейка Крауча про квантову фізику, паралельні світи та ціну вибору.", detail.description)
        // Spec-24 T9 (#170): the page's og:image is the imported cover.
        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp",
            detail.coverImageUrl
        )
        assertEquals(2, detail.chapters.size)
        assertEquals("https://arch.sound-books.net/4111/Темна матерія-01.mp3", detail.chapters[0].streamUrl)
        assertEquals("https://arch.sound-books.net/4111/Темна матерія-02.mp3", detail.chapters[1].streamUrl)
        // Chapter title comes from the m3u file name.
        assertEquals("Темна матерія-01", detail.chapters[0].title)
    }

    @Test
    fun `book page decodes a percent encoded playlist filename for the chapter title`() = runBlocking {
        val encodedStream =
            "https://arch.sound-books.net/3081/%D0%96%D0%B5%D1%80%D1%82%D0%B2%D0%B0%20%D0%B7%20%D0%BA%D0%BE%D1%81%D0%BC%D0%BE%D1%81%D1%83.mp3?expires=1788301675&md5=a3n-xTf87ppzJEGAx81Trg"
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/x.html" to bookPage,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to encodedStream
                )
            )
        )

        val chapter = adapter.fetchBookPage("https://sound-books.net/x.html").chapters.single()

        assertEquals("Жертва з космосу", chapter.title)
        assertEquals(encodedStream, chapter.streamUrl)
    }

    @Test
    fun `book page keeps malformed percent encoding as the chapter title fallback`() = runBlocking {
        val malformedStream = "https://arch.sound-books.net/3081/%D0%ZZжертва.mp3"
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/x.html" to bookPage,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to malformedStream
                )
            )
        )

        val chapter = adapter.fetchBookPage("https://sound-books.net/x.html").chapters.single()

        assertEquals("Глава 1", chapter.title)
        assertEquals(malformedStream, chapter.streamUrl)
    }

    // Spec-35 T5 — page profile fields, per field.
    @Test
    fun `book page preserves duration from Триває`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html" to bookPageFull,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to m3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html")

        assertEquals(9 * 3600L + 28 * 60L + 9L, detail.totalDurationSeconds)
    }

    @Test
    fun `book page preserves genres from Жанр links with prefix stripped`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html" to bookPageFull,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to m3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html")

        assertEquals(listOf("Зарубіжна література", "Фантастика"), detail.genres)
    }

    @Test
    fun `book page preserves rating from Рейтинг`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html" to bookPageFull,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to m3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html")

        assertEquals(4.0, detail.rating!!, 0.01)
    }

    @Test
    fun `book page preserves cover narrator and blurb together`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html" to bookPageFull,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to m3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html")

        assertEquals("Темна матерія", detail.title)
        assertEquals("Блейк Крауч", detail.author)
        assertEquals("Pik CAH4E3", detail.narrator)
        assertEquals("Роман Блейка Крауча про квантову фізику, паралельні світи та ціну вибору.", detail.description)
        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp",
            detail.coverImageUrl
        )
        // Negative findings on the page (inventory #237): no series/cycle,
        // no related rail — never fabricated (ADR-0014).
        assertNull(detail.series)
        assertTrue(detail.related.isEmpty())
    }

    // Negative tests — absent stays absent (ADR-0014).
    @Test
    fun `book page without duration keeps it null`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/x.html" to bookPageMinimal,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to m3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/x.html")
        assertNull(detail.totalDurationSeconds)
    }

    @Test
    fun `book page without genres keeps them empty`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/x.html" to bookPageMinimal,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to m3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/x.html")
        assertTrue(detail.genres.isEmpty())
    }

    @Test
    fun `book page without rating keeps it null`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/x.html" to bookPageMinimal,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to m3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/x.html")
        assertNull(detail.rating)
    }

    @Test
    fun `book page without series and related keeps them empty`() = runBlocking {
        // Inventory #237: soundbooks has no series/cycle markers and no
        // related rail — the KDoc documents the negative finding.
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/x.html" to bookPageFull,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to m3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/x.html")
        assertNull(detail.series)
        assertTrue(detail.related.isEmpty())
    }

    @Test
    fun `book page without a playlist yields no chapters`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(mapOf("https://sound-books.net/x.html" to "<html><body>no player</body></html>"))
        )

        assertTrue(adapter.fetchBookPage("https://sound-books.net/x.html").chapters.isEmpty())
    }

    // #449 — the live September 2026 playlist serves SIGNED track URLs with
    // percent-encoded Cyrillic filenames; the Chapter title must be readable
    // Ukrainian while the Source track URL stays byte-for-byte as served.
    private val signedM3u = """
        https://arch.sound-books.net/4111/%D0%96%D0%B5%D1%80%D1%82%D0%B2%D0%B0%20%D0%B7%20%D0%BA%D0%BE%D1%81%D0%BC%D0%BE%D1%81%D1%83-01.mp3?expires=1788000000&md5=abc123def456abc123def456abc12345
        https://arch.sound-books.net/4111/%D0%96%D0%B5%D1%80%D1%82%D0%B2%D0%B0%20%D0%B7%20%D0%BA%D0%BE%D1%81%D0%BC%D0%BE%D1%81%D1%83-02.mp3?expires=1788000000&md5=abc123def456abc123def456abc12346
    """.trimIndent()

    @Test
    fun `percent-encoded signed playlist yields readable titles and byte-stable urls`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/x.html" to bookPage,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to signedM3u
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/x.html")

        assertEquals(2, detail.chapters.size)
        // AC1+AC2: readable Ukrainian title, no extension, no signed query.
        assertEquals("Жертва з космосу-01", detail.chapters[0].title)
        assertEquals("Жертва з космосу-02", detail.chapters[1].title)
        // AC3: the Source track URL remains byte-for-byte as the playlist served it.
        assertEquals(
            "https://arch.sound-books.net/4111/%D0%96%D0%B5%D1%80%D1%82%D0%B2%D0%B0%20%D0%B7%20%D0%BA%D0%BE%D1%81%D0%BC%D0%BE%D1%81%D1%83-01.mp3?expires=1788000000&md5=abc123def456abc123def456abc12345",
            detail.chapters[0].streamUrl
        )
    }

    @Test
    fun `a malformed percent sequence falls back to a safe readable title`() = runBlocking {
        val broken = "https://arch.sound-books.net/4111/100%%D0broken-Chapter.mp3"
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/x.html" to bookPage,
                    "https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u" to broken
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/x.html")

        // AC4: the import must not fail and the title must stay readable —
        // the chapter number replaces an undecodable filename.
        assertEquals(1, detail.chapters.size)
        assertEquals("Глава 1", detail.chapters[0].title)
    }

    // Spec-24 T9 (#170): a page without og:image keeps a null cover — never
    // fabricated. (The relative-path form is resolved against the site origin,
    // mirroring the tile covers.)
    @Test
    fun `book page without a cover yields a null cover`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(mapOf("https://sound-books.net/y.html" to bookPageMinimal))
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/y.html")
        assertNull(detail.coverImageUrl)
    }

    @Test
    fun `relative og-image is resolved against the site origin`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/z.html" to bookPageRelativeCover,
                    "https://sound-books.net/uploads/public_files/2026-07/3001-soniachna-mashyna.m3u" to ""
                )
            )
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/z.html")
        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/soniachna-mashyna.webp",
            detail.coverImageUrl
        )
    }

    // Spec-35 T5 — card fields, per field.

    @Test
    fun `feed cards carry duration and genre from the listing`() = runBlocking {
        val adapter = SoundBooksAdapter(FakeFetcher(mapOf("https://sound-books.net/" to homepageWithCardExtras)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(2, books.size)
        // First card has duration, genre.
        assertEquals("Темна матерія", books[0].title)
        assertEquals("Блейк Крауч", books[0].author)
        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp",
            books[0].coverImageUrl
        )
        assertEquals(9 * 3600L + 28 * 60L, books[0].totalDurationSeconds)
        assertEquals("Зарубіжна література, Фантастика", books[0].genre)
        // Negative findings on card #237: no narrator, no rating, no series.
        assertEquals("", books[0].narrator)
        assertNull(books[0].seriesTitle)
        assertNull(books[0].seriesIndex)
        // Second card has no duration/genre — absent stays empty.
        assertEquals("Статут внутрішньої служби Збройних Сил України", books[1].title)
        assertEquals(0L, books[1].totalDurationSeconds)
        assertEquals("", books[1].genre)
    }

    @Test
    fun `feed card without duration and genre keeps them empty`() = runBlocking {
        val minimalListing = """
            <html><body>
            <div class="short-item">
                <div class="short-cols fx-row">
                    <a class="short-img img-fit" href="https://sound-books.net/kazka/2845-kin-vogon.html"><img data-src="/uploads/posts/2026-06/kin-vogon.webp" alt="Кінь-вогонь"></a>
                    <div class="short-desc fx-1">
                        <a class="short-title" href="https://sound-books.net/kazka/2845-kin-vogon.html">Кінь-вогонь - Автор</a>
                    </div>
                </div>
            </div>
            </body></html>
        """.trimIndent()
        val adapter = SoundBooksAdapter(FakeFetcher(mapOf("https://sound-books.net/" to minimalListing)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(1, books.size)
        assertEquals("Кінь-вогонь", books[0].title)
        assertEquals(0L, books[0].totalDurationSeconds)
        assertEquals("", books[0].genre)
        assertNull(books[0].seriesTitle)
    }

    @Test
    fun `feed narrator stays empty and rating is never on cards`() = runBlocking {
        // Inventory #237 negative findings: the listing never carries a
        // narrator («Читає» only on book pages) and the word «rating» on
        // the homepage is only a sort button, never a card score.
        val adapter = SoundBooksAdapter(FakeFetcher(mapOf("https://sound-books.net/" to homepageWithCardExtras)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals("", books[0].narrator)
        // SourceBook has no rating field; the page detail's rating stays on
        // the page surface only — no card should synthesize one.
        assertNull(books[0].seriesTitle)
    }

    // Real homepage markup (2026-08-17): the lazy-loaded cover tile
    // (<a class="short-img"><img data-src=… alt=…></a>) comes BEFORE the
    // bare-title text anchor for the same url, and the text anchor carries no
    // « - » separator. The old parser's BOOK_LINK matched the cover tile too
    // and its inner content — a raw <img> tag — won as the book's title
    // («Статут …» books came out titled `<img data-src=…>`).
    private val homepageWithLazyCoverFirst = """
        <html><body>
        <a class="short-img img-fit" href="https://sound-books.net/ukrainska-literatura/2850-statut-vnutrishnoi-sluzhby-zbroinykh-syl-ukrainy.html"><img data-src="/uploads/posts/2026-07/statut-vnutrishnoi-sluzhby-zbroinykh-syl-ukrainy.webp" alt="Статут Внутрішньої Служби Збройних Сил України"></a>
        <a class="short-title" href="https://sound-books.net/ukrainska-literatura/2850-statut-vnutrishnoi-sluzhby-zbroinykh-syl-ukrainy.html">Статут внутрішньої служби Збройних Сил України</a>
        <a class="short-img img-fit" href="https://sound-books.net/povisti-i-opovidannia/2847-pekelnyi-fonograf.html"><img data-src="/uploads/posts/2026-06/robert-blokh-pekelnyi-fonograf.webp" alt="Пекельний фонограф"></a>
        <a class="short-title" href="https://sound-books.net/povisti-i-opovidannia/2847-pekelnyi-fonograf.html">Пекельний фонограф - Роберт Блох</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `lazy cover tile never becomes the book title`() = runBlocking {
        val adapter = SoundBooksAdapter(FakeFetcher(mapOf("https://sound-books.net/" to homepageWithLazyCoverFirst)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(2, books.size)
        // The cover tile's raw <img> tag must never leak into the title.
        assertTrue("title must not contain an img tag, was: ${books[0].title}", !books[0].title.contains("<img", ignoreCase = true))
        assertEquals("Статут внутрішньої служби Збройних Сил України", books[0].title)
        assertEquals("", books[0].author)
        // The cover still rides along from the same tile.
        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/statut-vnutrishnoi-sluzhby-zbroinykh-syl-ukrainy.webp",
            books[0].coverImageUrl
        )
        // An author-bearing text anchor still splits title/author.
        assertEquals("Пекельний фонограф", books[1].title)
        assertEquals("Роберт Блох", books[1].author)
    }

    // A listing can render a book as image-only (no text anchor): the cover
    // tile's img alt is then the only title signal — the parser must fall
    // back to it instead of dropping the row.
    private val imageOnlyListing = """
        <html><body>
        <a class="short-img img-fit" href="https://sound-books.net/kazka/2845-kin-vogon.html"><img data-src="/uploads/posts/2026-06/kin-vogon.webp" alt="Кінь-вогонь"></a>
        </body></html>
    """.trimIndent()

    @Test
    fun `image-only listing falls back to the cover img alt`() = runBlocking {
        val adapter = SoundBooksAdapter(FakeFetcher(mapOf("https://sound-books.net/" to imageOnlyListing)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(1, books.size)
        assertEquals("Кінь-вогонь", books[0].title)
        assertEquals("https://sound-books.net/uploads/posts/2026-06/kin-vogon.webp", books[0].coverImageUrl)
    }

    // An alt-less cover tile must not lose its cover: the img alt is only an
    // optional title signal, never a requirement for the poster (hardening
    // from code-review, 2026-08-17).
    private val altlessCoverTile = """
        <html><body>
        <a class="short-img img-fit" href="https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html"><img data-src="/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp"></a>
        <a class="short-title" href="https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html">Темна матерія - Блейк Крауч</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `alt-less cover tile still yields the cover`() = runBlocking {
        val adapter = SoundBooksAdapter(FakeFetcher(mapOf("https://sound-books.net/" to altlessCoverTile)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(1, books.size)
        assertEquals("Темна матерія", books[0].title)
        assertEquals("Блейк Крауч", books[0].author)
        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp",
            books[0].coverImageUrl
        )
    }

    @Test
    fun `new feed splits title and author from the anchors`() = runBlocking {
        val adapter = SoundBooksAdapter(FakeFetcher(mapOf("https://sound-books.net/" to homepage)))

        val books = adapter.fetchNew(limit = 10)

        assertEquals(2, books.size)
        // The duplicate url collapses to one row, and the author-bearing
        // «Назва - Автор» tile wins over the bare-title cover tile.
        assertEquals("Темна матерія", books[0].title)
        assertEquals("Блейк Крауч", books[0].author)
        assertEquals("https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html", books[0].url)
        assertEquals("soundbooks", books[0].sourceId)
        // The cover tile's poster rides along (relative path -> absolute).
        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp",
            books[0].coverImageUrl
        )
        // An anchor without a separator stays a title-only entry; the entry
        // without a cover tile keeps a null cover.
        assertEquals("Статут внутрішньої служби Збройних Сил України", books[1].title)
        assertEquals("", books[1].author)
        assertEquals(null, books[1].coverImageUrl)
    }

    // Spec-15 T1: catalogue enumeration walks the homepage's category
    // sections and parses each category page (same tile markup as the feed).
    private val homeWithCategories = """
        <html><body>
        <a href="https://sound-books.net/fantastyka/">Фантастика</a>
        <a href="https://sound-books.net/roman/">Роман</a>
        <a href="https://sound-books.net/zhakhy/">Жахи</a>
        </body></html>
    """.trimIndent()

    private val fantastykaPage = """
        <html><body>
        <a class="short-img img-fit" href="https://sound-books.net/fantastyka/3001-soniachna-mashyna.html"><img data-src="/uploads/posts/2026-07/soniachna-mashyna.webp" alt="Сонячна машина"></a>
        <a class="short-title" href="https://sound-books.net/fantastyka/3001-soniachna-mashyna.html">Сонячна машина - Володимир Винниченко</a>
        <a class="short-title" href="https://sound-books.net/fantastyka/3002-tyha-planeta.html">Тиха планета</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `catalogue enumerates category pages into books with covers`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(
                mapOf(
                    "https://sound-books.net/" to homeWithCategories,
                    "https://sound-books.net/fantastyka/" to fantastykaPage,
                    "https://sound-books.net/roman/" to ""
                ),
                fallback = "<html><body></body></html>"
            ),
            pauseMillis = {}
        )

        val books = adapter.fetchCatalog(limit = 40)

        // The only populated category yields its two books; the empty category
        // contributes nothing; books are deduped by url.
        assertEquals(2, books.size)
        assertEquals("Сонячна машина", books[0].title)
        assertEquals("Володимир Винниченко", books[0].author)
        assertEquals("https://sound-books.net/fantastyka/3001-soniachna-mashyna.html", books[0].url)
        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/soniachna-mashyna.webp",
            books[0].coverImageUrl
        )
        assertEquals("Тиха планета", books[1].title)
        assertEquals("", books[1].author)
    }

    // Spec #462 ID5 (#468): the catalogue walk is no longer capped at the old
    // magic take(6) — it opens up to CATEGORY_PAGE_LIMIT pages, and the pause
    // between consecutive category requests keeps the human rhythm (spec-38).
    private fun categoryPage(id: Int, slug: String) = """
        <html><body>
        <a class="short-img img-fit" href="https://sound-books.net/$slug/$id-knyha-$id.html"><img data-src="/uploads/posts/2026-07/$id.webp" alt="Книга $id"></a>
        <a class="short-title" href="https://sound-books.net/$slug/$id-knyha-$id.html">Книга $id - Автор $id</a>
        </body></html>
    """.trimIndent()

    private fun homeWithNCategories(n: Int): String = buildString {
        append("<html><body>")
        repeat(n) { i ->
            val slug = listOf("fantastyka", "roman", "zhahy", "detektyv", "pryhodi", "poeziia", "dramaturhiia", "klassyka", "suchasna-proza", "istoriia")[i]
            append("""<a href="https://sound-books.net/$slug/">Категорія ${i + 1}</a>""")
        }
        append("</body></html>")
    }

    @Test
    fun `catalogue walks past the old six category pages with pacing pauses`() = runBlocking {
        val n = 8 // more than the old hard-coded take(6)
        val fetcher = FakeFetcher(
            buildMap {
                put("https://sound-books.net/", homeWithNCategories(n))
                val slugs = listOf("fantastyka", "roman", "zhahy", "detektyv", "pryhodi", "poeziia", "dramaturhiia", "klassyka")
                slugs.forEachIndexed { i, slug ->
                    put("https://sound-books.net/$slug/", categoryPage(i + 1, slug))
                }
            }
        )
        val pauses = mutableListOf<Long>()
        val adapter = SoundBooksAdapter(fetcher, pauseMillis = { pauses += it }, pacing = PacingPolicy(PacingParams(minPauseMillis = 100, maxPauseMillis = 100), Random(42)))

        val books = adapter.fetchCatalog(limit = 100)

        // All 8 categories contribute their book — the old take(6) would stop at 6.
        assertEquals(n, books.size)
        assertEquals("Книга 7", books[6].title)
        assertEquals("Книга 8", books[7].title)
        // One pause BETWEEN consecutive requests only: 8 pages → 7 pauses.
        assertEquals(List(n - 1) { 100L }, pauses)
    }

    @Test
    fun `catalogue honours the configurable category page limit`() = runBlocking {
        val fetcher = FakeFetcher(
            buildMap {
                put("https://sound-books.net/", homeWithNCategories(4))
                val slugs = listOf("fantastyka", "roman", "zhahy", "detektyv")
                slugs.forEachIndexed { i, slug ->
                    put("https://sound-books.net/$slug/", categoryPage(i + 1, slug))
                }
            },
            fallback = "<html><body></body></html>"
        )
        val adapter = SoundBooksAdapter(fetcher, categoryPageLimit = 2, pauseMillis = {})

        val books = adapter.fetchCatalog(limit = 100)

        // Only the first 2 category pages are opened — the limit is the
        // named, per-instance configurable CATEGORY_PAGE_LIMIT default.
        assertEquals(2, books.size)
        assertEquals("Книга 1", books[0].title)
        assertEquals("Книга 2", books[1].title)
    }
}
