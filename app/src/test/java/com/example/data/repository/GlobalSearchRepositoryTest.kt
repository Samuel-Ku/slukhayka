package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.source.SourceAdapter
import com.example.data.source.SourceBook
import com.example.data.source.SourceBookDetail
import com.example.data.source.SourceChapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-10 T4): the aggregated global search and the
 * import-from-result path, driven by injected fake adapters — no network.
 * Tests external behaviour: all sources are queried, results dedup into one
 * card per Work, feed-only sources are discovered by the query, and tapping a
 * result imports the Work with its source row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlobalSearchRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class FakeAdapter(
        override val sourceId: String,
        private val searchBooks: List<SourceBook> = emptyList(),
        private val feedBooks: List<SourceBook> = emptyList(),
        private val detail: SourceBookDetail? = null
    ) : SourceAdapter {
        override suspend fun search(query: String): List<SourceBook> =
            searchBooks.filter { it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            detail ?: SourceBookDetail("", "", url = url, chapters = emptyList())

        override suspend fun fetchNew(limit: Int): List<SourceBook> = feedBooks
    }

    private fun repo(vararg adapters: SourceAdapter) =
        AudiobookRepository(dao, context, autoSyncOnInit = false, sourceAdapters = adapters.toList())

    @Test
    fun `sluhayua urls map to the sluhayua source`() {
        val repository = repo()
        assertEquals("sluhayua", repository.sourceTypeOfUrl("https://sluhay.com.ua/4508492:taras-shevchenko-Єretik"))
        assertEquals("sluhayua", repository.sourceTypeOfUrl("https://mp3.sluhay.com.ua/Serdeshna/01.mp3"))
    }

    @Test
    fun `default adapter registry constructs with sluhayua registered`() {
        // The production default list (no injection) must build and know the
        // sluhayua source; adapter construction is inert (no network).
        val defaultRepo = AudiobookRepository(dao, context, autoSyncOnInit = false)
        assertEquals("sluhayua", defaultRepo.sourceTypeOfUrl("https://sluhay.com.ua/1965454:olga-kobilyanska-priroda"))
    }

    private fun book(title: String, author: String, sourceId: String) =
        SourceBook(title = title, author = author, url = "https://$sourceId.example/$title", sourceId = sourceId)

    @Test
    fun `global search queries all sources and merges deduped into one Work card`() = runBlocking {
        val repository = repo(
            FakeAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read"))),
            FakeAdapter("soundbooks", feedBooks = listOf(book("КОБЗАР", "Тарас Шевченко", "soundbooks")))
        )

        val results = repository.searchAllSources("кобзар")

        assertEquals(1, results.size)
        val card = results.single()
        assertEquals("Кобзар", card.title)
        assertEquals(listOf("4read", "soundbooks"), card.sources.map { it.sourceId })
    }

    @Test
    fun `feed-only sources are discovered by the query`() = runBlocking {
        val repository = repo(
            FakeAdapter("4read"), // no search hits
            FakeAdapter("lihtar", feedBooks = listOf(book("Лісова пісня", "", "lihtar")))
        )

        assertEquals(1, repository.searchAllSources("лісова").size)
        assertEquals("lihtar", repository.searchAllSources("лісова").single().sources.single().sourceId)
        // A query nothing matches still returns no junk.
        assertEquals(0, repository.searchAllSources("неіснуюча книга").size)
    }

    @Test
    fun `feed matches with blank authors are enriched from the book page so the merge forms`() = runBlocking {
        // audiobookmp3-style page: a real author but no narrator markup, so the
        // enriched entry's merge key (title + author) matches the 4read card.
        // (Narrator-sensitivity is covered at the pure seam: GlobalSearchMergeTest.)
        val detail = SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            url = "https://audiobook-mp3.com/uk/kobzar.html",
            chapters = listOf(SourceChapter("Розділ 1", "https://cdn.example.com/kobzar/01.mp3"))
        )
        val repository = repo(
            FakeAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read"))),
            FakeAdapter("audiobookmp3", feedBooks = listOf(book("Кобзар", "", "audiobookmp3")), detail = detail)
        )

        val results = repository.searchAllSources("кобзар")

        // The blank-author feed entry was enriched from its own book page and
        // now merges with the 4read result: one card, two source badges.
        assertEquals(1, results.size)
        assertEquals("Кобзар", results.single().title)
        assertEquals("Тарас Шевченко", results.single().author)
        assertEquals(listOf("4read", "audiobookmp3"), results.single().sources.map { it.sourceId })
    }

    @Test
    fun `global search is ephemeral - nothing is imported into Room`() = runBlocking {
        val repository = repo(
            FakeAdapter("4read", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "4read")))
        )

        repository.searchAllSources("кобзар")

        assertEquals(0, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `importFromSourceUrl fetches the page and imports the Work with its source row`() = runBlocking {
        val detail = SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            url = "https://sound-books.net/kobzar.html",
            chapters = listOf(SourceChapter("Розділ 1", "https://arch.sound-books.net/100/01.mp3"))
        )
        val repository = repo(FakeAdapter("soundbooks", detail = detail))

        val book = repository.importFromSourceUrl("soundbooks", detail.url)

        assertNotNull(book)
        assertEquals(1, dao.getAllAudiobooks().first().size)
        val sources = dao.getSourcesForBookSync(book!!.id)
        assertEquals(1, sources.size)
        assertEquals("soundbooks", sources.single().type)
        assertEquals(1, dao.getChaptersListForBook(book.id).size)
    }

    @Test
    fun `importFromSourceUrl returns null for unknown source or unplayable page`() = runBlocking {
        val repository = repo()
        assertNull(repository.importFromSourceUrl("nope", "https://unknown.example/x.html"))

        val unplayable = repo(
            FakeAdapter("soundbooks", detail = SourceBookDetail("К", "А", url = "https://u", chapters = emptyList()))
        )
        assertNull(unplayable.importFromSourceUrl("soundbooks", "https://u"))
        assertEquals(0, dao.getAllAudiobooks().first().size)
    }
}
