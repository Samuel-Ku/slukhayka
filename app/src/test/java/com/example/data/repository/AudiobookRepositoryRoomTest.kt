package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.db.BookmarkEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.testing.TestDataFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
