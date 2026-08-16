package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the spec-10 T3 SoundBooksAdapter. Markup mirrors real
 * sound-books.net pages captured during the T1 spike (book #4111, «Темна
 * матерія»).
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
    }    @Test
    fun `book page without a playlist yields no chapters`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(mapOf("https://sound-books.net/x.html" to "<html><body>no player</body></html>"))
        )


        assertTrue(adapter.fetchBookPage("https://sound-books.net/x.html").chapters.isEmpty())
    }

    // Spec-24 T9 (#170): a page without og:image keeps a null cover — never
    // fabricated. (The relative-path form is resolved against the site origin,
    // mirroring the tile covers.)
    @Test
    fun `book page without a cover yields a null cover`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(mapOf("https://sound-books.net/y.html" to bookPageWithoutCover))
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/y.html")
        assertEquals(null, detail.coverImageUrl)
    }

    @Test
    fun `relative og-image is resolved against the site origin`() = runBlocking {
        val adapter = SoundBooksAdapter(
            FakeFetcher(mapOf("https://sound-books.net/z.html" to bookPageRelativeCover))
        )

        val detail = adapter.fetchBookPage("https://sound-books.net/z.html")
        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/soniachna-mashyna.webp",
            detail.coverImageUrl
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
            )
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
}
