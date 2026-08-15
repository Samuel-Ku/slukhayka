package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.catalog.SourceCatalog
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.imports.LibraryImport
import com.example.data.source.SourceAdapter
import com.example.data.source.SourceBook
import com.example.data.source.SourceBookDetail
import com.example.data.source.SourceChapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-15 T3): the WebView catalogue hydration tool. Driven
 * by injected fake adapters — no network. Tests external behaviour: a crawl
 * imports every catalogue book into Room through the shared MergeKey path
 * (Work + chapters + source), re-hydration adds new books without duplicating
 * existing Works, a failing book never aborts the crawl, and an unknown source
 * is a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HydrationRepositoryTest {

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

    private open class FakeAdapter(
        override val sourceId: String,
        protected val catalogBooks: List<SourceBook>,
        private val detailFor: (SourceBook) -> SourceBookDetail
    ) : SourceAdapter {
        override val sessionBound: Boolean get() = true

        override suspend fun search(query: String): List<SourceBook> = emptyList()

        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()

        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = catalogBooks

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            catalogBooks.firstOrNull { it.url == url }?.let(detailFor)
                ?: SourceBookDetail("", "", url = url, chapters = emptyList())
    }

    // ADR-0002 (#138): the catalog tests construct the Source Catalog module
    // directly — no god module, no auto-sync on construction.
    private fun repo(vararg adapters: SourceAdapter) =
        SourceCatalog(dao, adapters.toList(), LibraryImport(dao, context, adapters.toList()))

    private fun book(title: String, author: String, sourceId: String) =
        SourceBook(title = title, author = author, url = "https://$sourceId.example/${title.lowercase()}.html", sourceId = sourceId)

    private fun detailOf(book: SourceBook, chapters: Int = 2) = SourceBookDetail(
        title = book.title,
        author = book.author,
        url = book.url,
        chapters = (0 until chapters).map { i ->
            SourceChapter(title = "${book.title} ${i + 1}", streamUrl = "https://cdn.example/${book.title}/track-$i.mp3")
        }
    )

    @Test
    fun `hydration imports every catalogued book into Room with chapters and sources`() = runBlocking {
        val pasazhyr = book("Пасажир", "Жан-Крістоф Гранже", "sluhay")
        val kobzar = book("Кобзар", "Тарас Шевченко", "sluhay")
        val repository = repo(FakeAdapter("sluhay", listOf(pasazhyr, kobzar), ::detailOf))

        val result = repository.hydrateWebSourceCatalog("sluhay")

        assertEquals(2, result.found)
        assertEquals(2, result.imported)
        assertEquals(0, result.failed)
        val stored = dao.getAllAudiobooks().first()
        assertEquals(2, stored.size)
        assertEquals("Пасажир", stored.first { it.title == "Пасажир" }.title)
        // Chapters imported per book; source rows attached.
        assertEquals(2, dao.getChaptersForBook(stored.first { it.title == "Пасажир" }.id).first().size)
        assertEquals(1, dao.getSourcesForBookSync(stored.first { it.title == "Пасажир" }.id).size)
    }

    @Test
    fun `re-hydration adds new books without duplicating existing Works`() = runBlocking {
        val pasazhyr = book("Пасажир", "Жан-Крістоф Гранже", "sluhay")
        val kobzar = book("Кобзар", "Тарас Шевченко", "sluhay")
        val repository = repo(FakeAdapter("sluhay", listOf(pasazhyr, kobzar), ::detailOf))

        repository.hydrateWebSourceCatalog("sluhay")
        // The catalogue grows — a new book appears after the first run.
        val novyj = book("Новий світ", "Новий автор", "sluhay")
        val repository2 = repo(FakeAdapter("sluhay", listOf(pasazhyr, kobzar, novyj), ::detailOf))
        val result = repository2.hydrateWebSourceCatalog("sluhay")

        // Only the NEW book was imported as a Work; the two existing Works
        // merged (their sources re-attached, no duplicates).
        assertEquals(3, result.found)
        assertEquals(1, result.imported)
        assertEquals(2, result.merged)
        assertEquals(0, result.failed)
        val stored = dao.getAllAudiobooks().first()
        // No duplicate Works — still exactly three books.
        assertEquals(3, stored.size)
        assertEquals(3, stored.map { it.title }.toSet().size)
    }

    @Test
    fun `a failing book never aborts the crawl`() = runBlocking {
        val pasazhyr = book("Пасажир", "Жан-Крістоф Гранже", "sluhay")
        val broken = book("Зламана", "Без автора", "sluhay")
        val repository = repo(
            object : FakeAdapter("sluhay", listOf(pasazhyr, broken), ::detailOf) {
                override suspend fun fetchBookPage(url: String): SourceBookDetail {
                    if (url == broken.url) throw RuntimeException("boom")
                    return detailOf(pasazhyr)
                }
            }
        )

        val result = repository.hydrateWebSourceCatalog("sluhay")

        assertEquals(2, result.found)
        assertEquals(1, result.imported)
        assertEquals(1, result.failed)
        assertEquals(1, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `unknown source is a no-op`() = runBlocking {
        val repository = repo(FakeAdapter("sluhay", listOf(book("Пасажир", "Жан-Крістоф Гранже", "sluhay")), ::detailOf))

        val result = repository.hydrateWebSourceCatalog("nope")

        assertEquals(0, result.found)
        assertEquals(0, result.imported)
        assertEquals(0, result.failed)
        assertTrue(dao.getAllAudiobooks().first().isEmpty())
    }
}
