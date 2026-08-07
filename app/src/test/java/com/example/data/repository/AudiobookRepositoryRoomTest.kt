package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.catalog.CatalogBook
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.db.BookmarkEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.data.imports.LocalAudioEntry
import com.example.testing.TestDataFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Repository tests against **in-memory Room** (spec #8 seam decision: the
 * user chose real SQL over `FakeAudiobookDao` for deletion/import semantics).
 *
 * Covers:
 *  - T1: a fresh database no longer receives the mock seed books.
 *  - T2: `deleteBook` cascades chapters/bookmarks/progress and local files.
 *  - T7: `importLocalAudioStream` materialises a playable single-chapter book.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudiobookRepositoryRoomTest {

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

    // ---------------------------------------------------------------------
    // T1: empty-catalog start
    // ---------------------------------------------------------------------

    @Test
    fun `fresh database starts empty of mock seed books`() = runBlocking {
        // autoSyncOnInit = true is the fresh-install path. In the test
        // environment the homepage fetch fails (or yields real catalogue
        // rows), but in no case may the old mock seed ids appear.
        AudiobookRepository(dao, context, autoSyncOnInit = true)
        Thread.sleep(1500) // give the background init coroutine time to run

        val books = dao.getAllAudiobooks().first()
        assertTrue(
            "no mock seed books may be inserted",
            books.none { it.id == "2172-ybson-vylyam-neyromant" || it.id == "cyber-dystopia-2077" || it.id == "4read-1984-orwell" }
        )
        assertTrue(dao.getChaptersListForBook("2172-ybson-vylyam-neyromant").isEmpty())
        assertTrue(dao.getBookmarksForBook("2172-ybson-vylyam-neyromant").first().isEmpty())
        assertNull(dao.getPlaybackProgressSync("2172-ybson-vylyam-neyromant"))
    }

    @Test
    fun `repository constructed without sync leaves database empty`() = runBlocking {
        AudiobookRepository(dao, context, autoSyncOnInit = false)
        assertTrue(dao.getAllAudiobooks().first().isEmpty())
    }

    // ---------------------------------------------------------------------
    // T2: cascading book deletion
    // ---------------------------------------------------------------------

    @Test
    fun `deleteBook cascades chapters bookmarks progress and local files`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)
        val book = TestDataFactory.dataBooks()[0]
        val localFile = File(context.filesDir, "cascade-${book.id}.mp3")
        localFile.writeBytes(ByteArray(64))

        val chapters = TestDataFactory.chaptersFor(book).mapIndexed { index, ch ->
            if (index == 0) ch.copy(localFilePath = localFile.absolutePath, isDownloaded = true) else ch
        }
        dao.insertAudiobooks(listOf(book))
        dao.insertChapters(chapters)
        dao.insertBookmark(
            BookmarkEntity(
                bookId = book.id,
                chapterIndex = 0,
                chapterTitle = "Глава 1",
                timestampSeconds = 10L,
                note = "фікстурна закладка",
                createdAt = TestDataFactory.FIXED_CLOCK_MS
            )
        )
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                bookId = book.id,
                currentChapterIndex = 0,
                currentPositionSeconds = 10L,
                lastListenedAt = TestDataFactory.FIXED_CLOCK_MS
            )
        )

        repo.deleteBook(book.id)

        assertNull(dao.getAudiobookById(book.id))
        assertTrue(dao.getChaptersListForBook(book.id).isEmpty())
        assertTrue(dao.getBookmarksForBook(book.id).first().isEmpty())
        assertNull(dao.getPlaybackProgressSync(book.id))
        assertFalse("local file must be deleted", localFile.exists())
    }

    @Test
    fun `deleteBook leaves other books untouched`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)
        val books = TestDataFactory.dataBooks()
        dao.insertAudiobooks(books)
        dao.insertChapters(TestDataFactory.dataChapters(books))

        repo.deleteBook(books[0].id)

        val remaining = dao.getAllAudiobooks().first()
        assertEquals(books.size - 1, remaining.size)
        assertEquals(books[1].id, remaining.first().id)
        assertEquals(TestDataFactory.CHAPTERS_PER_BOOK, dao.getChaptersListForBook(books[1].id).size)
    }

    // ---------------------------------------------------------------------
    // spec-9 T1: series metadata persistence
    // ---------------------------------------------------------------------

    @Test
    fun `upserting a catalog book persists its series metadata`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)

        repo.upsertCatalogBook(
            CatalogBook(
                id = "4read-7589-neostannij-bij",
                title = "Неостанній бій",
                author = "Костянтин Шелест",
                url = "https://4read.org/7589-neostannij-bij.html",
                coverImageUrl = null,
                seriesTitle = "Максим Темний",
                seriesUrl = "https://4read.org/xfsearch/cikl/maksym-temnyj/",
                seriesIndex = 7
            )
        )

        val stored = dao.getAudiobookById("4read-7589-neostannij-bij")
        assertNotNull(stored)
        assertEquals("Максим Темний", stored!!.seriesTitle)
        assertEquals("https://4read.org/xfsearch/cikl/maksym-temnyj/", stored.seriesUrl)
        assertEquals(7, stored.seriesIndex)
    }

    @Test
    fun `re-upserting a known book back-fills its series metadata without losing user state`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)
        val book = TestDataFactory.dataBooks()[0]
        // Insert a book that predates series metadata (e.g. from an earlier sync).
        dao.insertAudiobooks(listOf(book.copy(isFavorite = true, isDownloaded = true)))

        repo.upsertCatalogBook(
            CatalogBook(
                id = book.id,
                title = book.title,
                author = book.author,
                url = book.sourceUrl,
                coverImageUrl = null,
                seriesTitle = "Сага про Дріззта",
                seriesUrl = "https://4read.org/xfsearch/cikl/drizzt/",
                seriesIndex = 2
            )
        )

        val stored = dao.getAudiobookById(book.id)!!
        assertEquals("Сага про Дріззта", stored.seriesTitle)
        assertEquals(2, stored.seriesIndex)
        // User state must survive the back-fill.
        assertTrue(stored.isFavorite)
        assertTrue(stored.isDownloaded)
        // Unrelated fields untouched.
        assertEquals(book.title, stored.title)
    }

    @Test
    fun `upserting a book without series metadata leaves stored series untouched`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)
        dao.insertAudiobooks(
            listOf(
                TestDataFactory.dataBooks()[1].copy(
                    seriesTitle = "Старий цикл",
                    seriesUrl = "https://4read.org/xfsearch/cikl/old/",
                    seriesIndex = 1
                )
            )
        )

        repo.upsertCatalogBook(
            CatalogBook(
                id = TestDataFactory.dataBooks()[1].id,
                title = "1984",
                author = "Джордж Орвелл",
                url = "https://4read.org/1984.html",
                coverImageUrl = null,
                seriesTitle = null,
                seriesUrl = null,
                seriesIndex = null
            )
        )

        val stored = dao.getAudiobookById(TestDataFactory.dataBooks()[1].id)!!
        assertEquals("Старий цикл", stored.seriesTitle)
        assertEquals(1, stored.seriesIndex)
    }

    // ---------------------------------------------------------------------
    // T7: local audio import
    // ---------------------------------------------------------------------

    @Test
    fun `importLocalAudioStream creates a downloadable single-chapter book`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)

        val book = repo.importLocalAudioStream("Моя книга.mp3", ByteArrayInputStream(ByteArray(32)))

        assertEquals("Моя книга", book.title)
        assertEquals("Локальний файл", book.author)
        assertTrue(book.isDownloaded)
        assertEquals(1, book.totalChapters)

        val chapters = dao.getChaptersListForBook(book.id)
        assertEquals(1, chapters.size)
        val chapter = chapters.first()
        assertEquals(book.id, chapter.bookId)
        assertEquals(0, chapter.chapterIndex)
        assertTrue("local file must exist", File(chapter.localFilePath!!).exists())
        assertEquals(chapter.localFilePath, chapter.streamUrl)

        // The imported book shows in the downloaded set.
        val downloaded = dao.getDownloadedAudiobooks().first()
        assertTrue(downloaded.any { it.id == book.id })
    }

    // ---------------------------------------------------------------------
    // Block 4: folder import (SAF tree) — grouping core
    // ---------------------------------------------------------------------

    @Test
    fun `importAudioEntries groups each folder into one book and root files into individual books`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)

        val result = repo.importAudioEntries(
            listOf(
                LocalAudioEntry("01.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) },
                LocalAudioEntry("02.mp3", "Кобзар") { ByteArrayInputStream(ByteArray(16)) },
                LocalAudioEntry("Лісова пісня.mp3", null) { ByteArrayInputStream(ByteArray(16)) },
                LocalAudioEntry("Промова.mp3", "Історія") { ByteArrayInputStream(ByteArray(16)) }
            )
        )

        assertEquals(3, result.booksImported)
        assertEquals(4, result.filesImported)
        assertEquals(0, result.skippedFiles)

        val books = dao.getAllAudiobooks().first()
        val folderBook = books.first { it.title == "Кобзар" }
        val rootBook = books.first { it.title == "Лісова пісня" }
        val historyBook = books.first { it.title == "Історія" }

        // Folder book: two chapters, all downloaded, pointing at copied files.
        val folderChapters = dao.getChaptersListForBook(folderBook.id)
        assertEquals(2, folderChapters.size)
        assertTrue(folderChapters.all { it.isDownloaded && it.localFilePath != null })
        assertTrue(folderChapters.all { File(it.localFilePath!!).exists() })

        // Root book: single chapter, title = file name (T7 behaviour).
        assertEquals(1, dao.getChaptersListForBook(rootBook.id).size)
        assertEquals(1, dao.getChaptersListForBook(historyBook.id).size)
    }

    @Test
    fun `importAudioEntries sorts folder chapters by natural file name order`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)

        repo.importAudioEntries(
            listOf(
                LocalAudioEntry("track10.mp3", "Сага") { ByteArrayInputStream(ByteArray(8)) },
                LocalAudioEntry("track2.mp3", "Сага") { ByteArrayInputStream(ByteArray(8)) },
                LocalAudioEntry("track1.mp3", "Сага") { ByteArrayInputStream(ByteArray(8)) }
            )
        )

        val book = dao.getAllAudiobooks().first().first { it.title == "Сага" }
        val chapters = dao.getChaptersListForBook(book.id)
        assertEquals("track1", chapters[0].title)
        assertEquals("track2", chapters[1].title)
        assertEquals("track10", chapters[2].title)
        assertEquals(listOf(0, 1, 2), chapters.map { it.chapterIndex })
    }

    @Test
    fun `importAudioEntries skips unreadable files without crashing and skips empty folders`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)

        val result = repo.importAudioEntries(
            listOf(
                LocalAudioEntry("broken.mp3", "Поламана") { throw java.io.IOException("no access") },
                LocalAudioEntry("good.mp3", "Поламана") { ByteArrayInputStream(ByteArray(8)) },
                LocalAudioEntry("empty.mp3", "Пуста") { throw java.io.IOException("no access") }
            )
        )

        assertEquals(1, result.booksImported)
        assertEquals(1, result.filesImported)
        assertEquals(2, result.skippedFiles)

        val books = dao.getAllAudiobooks().first()
        // Only the folder with a readable file produced a book; the all-broken
        // folder produced none.
        assertEquals(listOf("Поламана"), books.map { it.title })
    }

    @Test
    fun `importAudioEntries keeps same-named folders from different branches separate`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)

        repo.importAudioEntries(
            listOf(
                LocalAudioEntry("01.mp3", "SeriesA/Кобзар") { ByteArrayInputStream(ByteArray(8)) },
                LocalAudioEntry("01.mp3", "SeriesB/Кобзар") { ByteArrayInputStream(ByteArray(8)) }
            )
        )

        val books = dao.getAllAudiobooks().first()
        // Two distinct books despite the identical folder names — grouping is
        // by the full relative path, not the bare folder name. Each holds only
        // its own chapter.
        assertEquals(2, books.size)
        assertEquals(2, books.sumOf { dao.getChaptersListForBook(it.id).size })
    }

    @Test
    fun `importAudioEntries preserves the original file extension`() = runBlocking {
        val repo = AudiobookRepository(dao, context, autoSyncOnInit = false)

        repo.importAudioEntries(
            listOf(
                LocalAudioEntry("Розділ.ogg", "Книга") { ByteArrayInputStream(ByteArray(8)) },
                LocalAudioEntry("Глава.m4a", "Книга") { ByteArrayInputStream(ByteArray(8)) }
            )
        )

        val book = dao.getAllAudiobooks().first().first { it.title == "Книга" }
        val paths = dao.getChaptersListForBook(book.id).mapNotNull { it.localFilePath }
        assertTrue(paths.any { it.endsWith(".ogg") })
        assertTrue(paths.any { it.endsWith(".m4a") })
    }
}
