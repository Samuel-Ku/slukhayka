package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM fixture tests for the spec-37 live collection source
 * (sound-books.net TOP-100). Payload mirrors the real
 * `/top-100-audioknyg-nashogu-saitu.html` shape: `short-item` tiles with
 * `short-title` anchors «Назва - Автор». No network — the shared fetcher
 * serves canned text (ADR-0006). The main-list scoping rule is pinned:
 * sidebar / comments / related blocks that also link to sound-books books
 * must be ignored.
 */
class SoundBooksTopSourceTest {

    // Main list: two valid tiles, one with an internal dash in the title,
    // one without author (no separator), one with blank title.
    // Plus three decoy blocks that carry book links but NOT short-title class.
    private val topFixture = """
        <div class="sect-bg"><h1>ТОП - 100 популярних аудіокниг</h1>
        <div class="short-item">
            <a class="short-title" href="https://sound-books.net/zarubizhna-literatura/1103-1984.html">1984  - Джордж Орвелл</a>
        </div>
        <div class="short-item">
            <a class="short-title" href="https://sound-books.net/ukrainska-literatura/254-kaidasheva-simia.html">Кайдашева сім&#039;я  - Іван Нечуй-Левицький</a>
        </div>
        <div class="short-item">
            <a class="short-title" href="https://sound-books.net/zarubizhna-literatura/999-dash-title.html">Війна і мир - Том 1 - Лев Толстой</a>
        </div>
        <div class="short-item">
            <a class="short-title" href="https://sound-books.net/ukrainska-literatura/555-no-author.html">Книга без автора</a>
        </div>
        <div class="short-item">
            <a class="short-title" href="https://sound-books.net/ukrainska-literatura/556-blank.html">   - Тарас Шевченко</a>
        </div>
        </div>
        <!-- sidebar decoy: same book link but different markup -->
        <div class="side-box">
            <a href="https://sound-books.net/zarubizhna-literatura/1103-1984.html">Sidebar decoy</a>
            <a href="https://sound-books.net/ukrainska-literatura/254-kaidasheva-simia.html">Sidebar second</a>
        </div>
        <!-- comments decoy -->
        <div class="comments">
            <a href="https://sound-books.net/zarubizhna-literatura/1103-1984.html">Comment link</a>
        </div>
        <!-- related block decoy -->
        <div class="related">
            <a href="https://sound-books.net/zarubizhna-literatura/1103-1984.html">Related link</a>
        </div>
    """.trimIndent()

    @Test
    fun `parses only the main-list tiles into one collection`() = runBlocking {
        val source = SoundBooksTopSource(
            fetcher = FakeFetcher(mapOf(SoundBooksTopSource.TOP_ENDPOINT to topFixture))
        )

        val lists = source.fetchLiveCollections()

        assertEquals(1, lists.size)
        val list = lists.single()
        assertEquals("soundbooks-top", list.id)
        assertEquals("ТОП-100 sound-books", list.name)
        // 3 valid entries: 1984, Кайдашева сім'я, Війна і мир - Том 1.
        // No-author and blank-title rows are dropped; 4 decoy links ignored.
        assertEquals(3, list.entries.size)
        assertEquals("Джордж Орвелл", list.entries[0].author)
        assertEquals("1984", list.entries[0].title)
        assertEquals("Іван Нечуй-Левицький", list.entries[1].author)
        assertEquals("Кайдашева сім'я", list.entries[1].title)
    }

    @Test
    fun `split at the last dash so titles with dashes stay intact`() = runBlocking {
        val source = SoundBooksTopSource(
            fetcher = FakeFetcher(mapOf(SoundBooksTopSource.TOP_ENDPOINT to topFixture))
        )

        val entry = source.fetchLiveCollections().single().entries[2]

        assertEquals("Лев Толстой", entry.author)
        assertEquals("Війна і мир - Том 1", entry.title)
    }

    @Test
    fun `a fetch failure yields no collection - never throws`() = runBlocking {
        val source = SoundBooksTopSource(fetcher = FakeFetcher(emptyMap()))

        assertTrue(source.fetchLiveCollections().isEmpty())
    }

    @Test
    fun `a changed upstream shape yields no collection`() = runBlocking {
        val source = SoundBooksTopSource(
            fetcher = FakeFetcher(mapOf(SoundBooksTopSource.TOP_ENDPOINT to "<html><body>No short-title here</body></html>"))
        )

        assertTrue(source.fetchLiveCollections().isEmpty())
    }

    @Test
    fun `limit caps the entries`() = runBlocking {
        val source = SoundBooksTopSource(
            fetcher = FakeFetcher(mapOf(SoundBooksTopSource.TOP_ENDPOINT to topFixture)),
            limit = 1
        )

        val lists = source.fetchLiveCollections()

        assertEquals(1, lists.single().entries.size)
        assertEquals("1984", lists.single().entries.single().title)
    }

    @Test
    fun `blank page yields no collection`() = runBlocking {
        val source = SoundBooksTopSource(
            fetcher = FakeFetcher(mapOf(SoundBooksTopSource.TOP_ENDPOINT to "   "))
        )

        assertTrue(source.fetchLiveCollections().isEmpty())
    }
}
