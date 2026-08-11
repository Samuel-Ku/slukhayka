package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.source.SourceAdapter
import com.example.data.source.SourceBook
import com.example.data.source.SourceBookDetail
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
 * Repository seam (spec-10 T5): the per-source «Нове з кожного джерела»
 * rows, driven by injected fake adapters — no network. Tests external
 * behaviour: one row per non-empty source, empty sources contribute no row,
 * and a failing source hides only its own row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceFeedsRepositoryTest {

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
        private val feedBooks: List<SourceBook>,
        private val failFetchNew: Boolean = false
    ) : SourceAdapter {
        override suspend fun search(query: String): List<SourceBook> = emptyList()

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())

        override suspend fun fetchNew(limit: Int): List<SourceBook> {
            if (failFetchNew) throw java.io.IOException("fixture failure")
            return feedBooks
        }
    }

    private fun repo(vararg adapters: SourceAdapter) =
        AudiobookRepository(dao, context, autoSyncOnInit = false, sourceAdapters = adapters.toList())

    private fun book(sourceId: String, title: String) =
        SourceBook(title = title, author = "", url = "https://$sourceId.example/$title", sourceId = sourceId)

    @Test
    fun `refreshSourceFeeds publishes one row per non-empty source`() = runBlocking {
        val repository = repo(
            FakeAdapter("soundbooks", listOf(book("soundbooks", "Темна матерія"))),
            FakeAdapter("lihtar", listOf(book("lihtar", "Лісова пісня")))
        )

        val feeds = repository.refreshSourceFeeds()

        assertEquals(listOf("lihtar", "soundbooks"), feeds.map { it.sourceId }.sorted())
        val soundbooks = feeds.first { it.sourceId == "soundbooks" }
        assertEquals("Sound-Books", soundbooks.sourceName)
        assertEquals(1, soundbooks.books.size)
    }

    @Test
    fun `sources with empty feeds contribute no row`() = runBlocking {
        val repository = repo(
            FakeAdapter("soundbooks", emptyList()),
            FakeAdapter("audiobookmp3", listOf(book("audiobookmp3", "Книга")))
        )

        val feeds = repository.refreshSourceFeeds()

        assertEquals(listOf("audiobookmp3"), feeds.map { it.sourceId })
    }

    @Test
    fun `a failing source hides only its own row`() = runBlocking {
        val repository = repo(
            FakeAdapter("soundbooks", listOf(book("soundbooks", "Темна матерія"))),
            FakeAdapter("lihtar", emptyList(), failFetchNew = true),
            FakeAdapter("audiobookmp3", listOf(book("audiobookmp3", "Книга")))
        )

        val feeds = repository.refreshSourceFeeds()

        // The throwing lihtar contributes nothing; the others still render.
        assertEquals(setOf("soundbooks", "audiobookmp3"), feeds.map { it.sourceId }.toSet())
        assertEquals(2, feeds.size)
    }

    @Test
    fun `4read is excluded from the feed rows - its new arrivals render elsewhere`() = runBlocking {
        val repository = repo(
            FakeAdapter("4read", listOf(book("4read", "Неостанній бій"))),
            FakeAdapter("soundbooks", listOf(book("soundbooks", "Темна матерія")))
        )

        val feeds = repository.refreshSourceFeeds()

        // 4read's «Нове» is already covered by the «Нове на 4read» rows.
        assertTrue(feeds.none { it.sourceId == "4read" })
        assertEquals(listOf("soundbooks"), feeds.map { it.sourceId })
    }
}
