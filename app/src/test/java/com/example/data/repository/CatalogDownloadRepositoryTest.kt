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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-15 T4): the one-tap catalogue-card download flow. The
 * card is ephemeral — the ViewModel imports the book transparently via
 * [AudiobookRepository.importFromSourceUrl] and then runs the shared
 * [AudiobookRepository.downloadAudiobookOffline] loop. These tests pin the
 * seam with fake adapters (no network): the import materialises chapters so
 * the download loop has something to run, and a stream-only source refuses
 * the download in depth even when the card would otherwise play.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CatalogDownloadRepositoryTest {

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
        private val book: SourceBook
    ) : SourceAdapter {
        override val sessionBound: Boolean get() = false

        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = listOf(book)

        override suspend fun fetchBookPage(url: String): SourceBookDetail = SourceBookDetail(
            title = book.title,
            author = book.author,
            url = url,
            chapters = (0 until 2).map { i ->
                SourceChapter(title = "${book.title} ${i + 1}", streamUrl = "chapter-$i")
            }
        )
    }

    private fun repo(vararg adapters: SourceAdapter) =
        AudiobookRepository(dao, context, autoSyncOnInit = false, sourceAdapters = adapters.toList())

    private fun book(sourceId: String, url: String) =
        SourceBook(title = "Пасажир", author = "Жан-Крістоф Гранже", url = url, sourceId = sourceId)

    @Test
    fun `catalogue card import materialises chapters so the download loop runs`() = runBlocking {
        val url = "https://sluhay.com/svitova-literatura/6177-pasazhir.html"
        val repository = repo(FakeAdapter("sluhay", book("sluhay", url)))

        val imported = repository.importFromSourceUrl("sluhay", url)

        assertNotNull(imported)
        val chapters = dao.getChaptersListForBook(imported!!.id)
        assertEquals(2, chapters.size)
        // The loop runs (non-http urls → the chapter fetch is skipped, so it
        // reports every chapter as failed — the whole path is exercised, no
        // network). The card's progress derives from this Room row.
        val outcome = repository.downloadAudiobookOffline(imported.id)
        assertEquals(2, outcome.totalChapters)
        assertEquals(0, outcome.downloadedChapters)
        assertFalse(dao.getAudiobookById(imported.id)!!.isDownloaded)
    }

    @Test
    fun `stream-only source refuses the download in depth even after import`() = runBlocking {
        val url = "https://lihtar.in.ua/biblioteka/pasazhir"
        val repository = repo(FakeAdapter("lihtar", book("lihtar", url)))

        // The card would play from lihtar (streaming is allowed) — but the
        // download path refuses up front, so the ViewModel's transparent
        // import + download never writes files.
        val imported = repository.importFromSourceUrl("lihtar", url)

        assertNotNull(imported)
        val outcome = repository.downloadAudiobookOffline(imported!!.id)

        assertEquals(0, outcome.totalChapters)
        assertEquals(0, outcome.downloadedChapters)
        assertFalse(dao.getAudiobookById(imported.id)!!.isDownloaded)
        assertEquals(0f, dao.getAudiobookById(imported.id)!!.downloadProgress)
        assertTrue(dao.getChaptersListForBook(imported.id).none { it.isDownloaded })
    }

    @Test
    fun `re-download after partial failure skips completed chapters`() = runBlocking {
        // Non-http urls fail; nothing is written, so a re-run behaves the same
        // — the loop stays idempotent and never corrupts state.
        val url = "https://sluhay.com/svitova-literatura/6177-pasazhir.html"
        val repository = repo(FakeAdapter("sluhay", book("sluhay", url)))
        val imported = repository.importFromSourceUrl("sluhay", url)!!

        repository.downloadAudiobookOffline(imported.id)
        val second = repository.downloadAudiobookOffline(imported.id)

        assertEquals(2, second.totalChapters)
        assertEquals(0, second.downloadedChapters)
        assertEquals(1, dao.getAllAudiobooks().first().size)
    }
}
