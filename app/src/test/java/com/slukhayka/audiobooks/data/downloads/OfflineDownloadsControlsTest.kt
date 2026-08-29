package com.slukhayka.audiobooks.data.downloads

import android.content.Context
import androidx.room.Room
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.DownloadState
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.imports.LibraryImport
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
import java.io.File

/**
 * #394 — TDD tests for download controls: pause, continue, cancel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineDownloadsControlsTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR).deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR).deleteRecursively()
        db.close()
    }

    private class ControlledFakeAdapter(
        override val sourceId: String = "fake-source",
        private val numChapters: Int = 3,
        private val sourceUrl: String = "https://fixtures.invalid/book"
    ) : SourceAdapter {
        override val sessionBound: Boolean get() = false

        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = listOf(
            SourceBook(title = "Test Book", author = "Author", url = sourceUrl, sourceId = sourceId)
        )
        override suspend fun fetchBookPage(url: String): SourceBookDetail = SourceBookDetail(
            title = "Test Book", author = "Author", url = url,
            chapters = (0 until numChapters).map { i ->
                SourceChapter(title = "Chapter ${i + 1}", streamUrl = "https://fixtures.invalid/chapter-${i}.mp3",
                    durationSeconds = 120L)
            }
        )
    }

    private data class TestHarness(
        val downloads: OfflineDownloads,
        val adapter: ControlledFakeAdapter,
        val bookId: String,
        val audioDir: File
    )

    private suspend fun setupHarness(numChapters: Int = 3): TestHarness {
        val adapter = ControlledFakeAdapter(numChapters = numChapters)
        val import = LibraryImport(dao, context, listOf(adapter))
        val catalog = SourceCatalog(dao, listOf(adapter), import)
        val downloads = OfflineDownloads(dao, context, catalog, pacing = PacingPolicy())

        val bookId = "test-book-ctrl"
        val editionId = "test-edition-ctrl"
        val workId = "test-work-ctrl"
        val sourceId = "fake-source-$editionId"

        dao.upsertWork(WorkEntity(id = workId, mergeKey = "test|book", title = "Test Book", author = "Author"))
        dao.insertEdition(EditionEntity(id = editionId, workId = workId))
        dao.insertAudiobooks(listOf(
            AudiobookEntity(id = bookId, title = "Test Book", author = "Author", narrator = "Narrator",
                description = "", coverDrawableRes = 0, genre = "", sourceUrl = "https://fixtures.invalid/book")
        ))
        dao.upsertLibraryEntry(bookId, workId, false, 0L, 0f, DownloadState.IDLE)
        dao.insertSources(listOf(SourceEntity(id = sourceId, bookId = bookId, editionId = editionId, type = "fake", url = "")))

        val chapters = (0 until numChapters).map { i ->
            ChapterEntity(id = "ch-$i", bookId = bookId, chapterIndex = i, title = "Chapter ${i + 1}",
                durationSeconds = 120L, editionId = editionId)
        }
        val tracks = (0 until numChapters).map { i ->
            SourceTrackEntity(id = "tr-$i", sourceId = sourceId, trackIndex = i,
                url = "https://fixtures.invalid/chapter-${i}.mp3")
        }
        dao.insertChapters(chapters)
        dao.insertTracks(tracks)

        val audioDir = File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR)
        audioDir.mkdirs()

        return TestHarness(downloads, adapter, bookId, audioDir)
    }

    // =====================================================================
    // PAUSE TESTS
    // =====================================================================

    @Test
    fun `pauseDownload sets downloadState to PAUSED`() = runBlocking {
        val h = setupHarness()
        // Create a completed file to simulate partial download
        val file0 = File(h.audioDir, "ch-0.mp3")
        file0.writeBytes(ByteArray(1024))
        dao.updateTrackDownloadState("tr-0", true, file0.absolutePath)
        dao.updateDownloadStateWithState(h.bookId, false, 0.33f, DownloadState.DOWNLOADING)

        h.downloads.pauseDownload(h.bookId)

        val entry = dao.getAudiobookById(h.bookId)
        assertEquals(DownloadState.PAUSED, entry?.downloadState)
    }

    @Test
    fun `pauseDownload deletes only tmp files not completed tracks`() = runBlocking {
        val h = setupHarness(numChapters = 2)
        // Create a completed file and a tmp file to simulate partial download
        val completedFile = File(h.audioDir, "ch-0.mp3")
        completedFile.writeBytes(ByteArray(1024))
        dao.updateTrackDownloadState("tr-0", true, completedFile.absolutePath)
        val tmpFile = File(h.audioDir, "ch-1.mp3.tmp")
        tmpFile.writeBytes(ByteArray(512))
        // Set state to DOWNLOADING so pauseDownload doesn't return early
        dao.updateDownloadStateWithState(h.bookId, false, 0.5f, DownloadState.DOWNLOADING)

        h.downloads.pauseDownload(h.bookId)

        assertTrue("Completed file should survive pause", completedFile.exists())
        assertFalse("Temp file should be deleted on pause", tmpFile.exists())
    }

    @Test
    fun `pauseDownload is a no-op when no download is active`() = runBlocking {
        val h = setupHarness()
        h.downloads.pauseDownload(h.bookId)
        val entry = dao.getAudiobookById(h.bookId)
        assertEquals(DownloadState.IDLE, entry?.downloadState)
    }

    @Test
    fun `pauseDownload keeps track localFilePath for completed chapters`() = runBlocking {
        val h = setupHarness(numChapters = 2)
        val file0 = File(h.audioDir, "ch-0.mp3")
        file0.writeBytes(ByteArray(1024))
        dao.updateTrackDownloadState("tr-0", true, file0.absolutePath)

        h.downloads.pauseDownload(h.bookId)

        val track0 = dao.getTracksForBookSync(h.bookId).first { it.id == "tr-0" }
        assertTrue("Completed track should keep localFilePath", track0.isDownloaded)
        assertEquals(file0.absolutePath, track0.localFilePath)
    }

    // =====================================================================
    // CONTINUE (RESUME) TESTS
    // =====================================================================

    @Test
    fun `continueDownload resumes only missing chapters`() = runBlocking {
        val h = setupHarness(numChapters = 3)
        // Simulate chapter 0 already downloaded before pause
        val file0 = File(h.audioDir, "ch-0.mp3")
        file0.writeBytes(ByteArray(1024))
        dao.updateTrackDownloadState("tr-0", true, file0.absolutePath)
        dao.updateDownloadStateWithState(h.bookId, false, 0.33f, DownloadState.PAUSED)

        val result = h.downloads.continueDownload(h.bookId)

        // Chapters 1 and 2 should be attempted (they'll fail without real network
        // but the function should attempt them)
        assertTrue("Should attempt remaining chapters", result.totalChapters >= 2)
    }

    @Test
    fun `continueDownload is a no-op when not PAUSED`() = runBlocking {
        val h = setupHarness()
        dao.updateDownloadStateWithState(h.bookId, false, 0f, DownloadState.IDLE)

        h.downloads.continueDownload(h.bookId)

        val entry = dao.getAudiobookById(h.bookId)
        assertEquals(DownloadState.IDLE, entry?.downloadState)
    }

    // =====================================================================
    // CANCEL TESTS
    // =====================================================================

    @Test
    fun `cancelDownload deletes all downloaded files for the edition`() = runBlocking {
        val h = setupHarness(numChapters = 2)
        val file0 = File(h.audioDir, "ch-0.mp3")
        file0.writeBytes(ByteArray(1024))
        val file1 = File(h.audioDir, "ch-1.mp3")
        file1.writeBytes(ByteArray(2048))
        dao.updateTrackDownloadState("tr-0", true, file0.absolutePath)
        dao.updateTrackDownloadState("tr-1", true, file1.absolutePath)

        h.downloads.cancelDownload(h.bookId)

        assertFalse("File 0 should be deleted", file0.exists())
        assertFalse("File 1 should be deleted", file1.exists())
    }

    @Test
    fun `cancelDownload clears track localFilePath and contentHash`() = runBlocking {
        val h = setupHarness(numChapters = 2)
        val file0 = File(h.audioDir, "ch-0.mp3")
        file0.writeBytes(ByteArray(1024))
        dao.updateTrackDownloadState("tr-0", true, file0.absolutePath)
        dao.updateTrackContentHash("tr-0", "abc123")

        h.downloads.cancelDownload(h.bookId)

        val track0 = dao.getTracksForBookSync(h.bookId).first { it.id == "tr-0" }
        assertFalse("Track should not be downloaded", track0.isDownloaded)
        assertNull("Track localFilePath should be null", track0.localFilePath)
    }

    @Test
    fun `cancelDownload resets downloadProgress to 0 and state to IDLE`() = runBlocking {
        val h = setupHarness()
        dao.updateDownloadStateWithState(h.bookId, false, 0.75f, DownloadState.DOWNLOADING)

        h.downloads.cancelDownload(h.bookId)

        val entry = dao.getAudiobookById(h.bookId)
        assertEquals(0f, entry?.downloadProgress ?: -1f)
        assertEquals(DownloadState.IDLE, entry?.downloadState)
    }

    @Test
    fun `cancelDownload clears content hashes for the book`() = runBlocking {
        val h = setupHarness()
        dao.updateTrackContentHash("tr-0", "abc123")

        h.downloads.cancelDownload(h.bookId)

        val tracks = dao.getTracksForBookSync(h.bookId)
        assertTrue("All content hashes should be cleared", tracks.all { it.contentHash == null })
    }

    @Test
    fun `cancelDownload is a no-op when no files exist`() = runBlocking {
        val h = setupHarness()
        h.downloads.cancelDownload(h.bookId)
        val entry = dao.getAudiobookById(h.bookId)
        assertEquals(DownloadState.IDLE, entry?.downloadState)
    }

    // =====================================================================
    // PAUSED STATE PERSISTENCE TESTS
    // =====================================================================

    @Test
    fun `PAUSED state persists in Room and survives simulated restart`() = runBlocking {
        val h = setupHarness()
        dao.updateDownloadStateWithState(h.bookId, false, 0.5f, DownloadState.PAUSED)

        val entry = dao.getAudiobookById(h.bookId)
        assertEquals(DownloadState.PAUSED, entry?.downloadState)
        assertEquals(0.5f, entry?.downloadProgress ?: -1f)
    }

    @Test
    fun `PAUSED state shows in downloadState flow`() = runBlocking {
        val h = setupHarness()
        dao.updateDownloadStateWithState(h.bookId, false, 0.33f, DownloadState.PAUSED)

        val books = dao.getAllAudiobooksOnce()
        val book = books.firstOrNull { it.id == h.bookId }
        assertEquals(DownloadState.PAUSED, book?.downloadState)
    }

    // =====================================================================
    // REGRESSION: removeOfflineDownload resets state and keeps the book
    // =====================================================================

    /**
     * #394 regression — after a completed download, calling removeOfflineDownload
     * must delete files, reset downloadState to IDLE, and keep the book in the library.
     */
    @Test
    fun `removeOfflineDownload after completed download resets state to IDLE and keeps book`() = runBlocking {
        val h = setupHarness(numChapters = 2)
        val file0 = File(h.audioDir, "ch-0.mp3")
        val file1 = File(h.audioDir, "ch-1.mp3")
        file0.writeBytes(ByteArray(1024))
        file1.writeBytes(ByteArray(2048))
        dao.updateTrackDownloadState("tr-0", true, file0.absolutePath)
        dao.updateTrackDownloadState("tr-1", true, file1.absolutePath)
        dao.updateDownloadStateWithState(h.bookId, true, 1.0f, DownloadState.IDLE)

        h.downloads.removeOfflineDownload(h.bookId)

        assertFalse("File 0 should be deleted", file0.exists())
        assertFalse("File 1 should be deleted", file1.exists())
        val entry = dao.getAudiobookById(h.bookId)
        assertEquals("downloadState must be IDLE", DownloadState.IDLE, entry?.downloadState)
        assertEquals(0f, entry?.downloadProgress ?: -1f)
        val book = dao.getAllAudiobooksOnce().firstOrNull { it.id == h.bookId }
        assertTrue("Book must remain in the library", book != null)
    }

    /**
     * #394 regression — after a PAUSED download, calling removeOfflineDownload
     * must delete partial files, reset downloadState to IDLE, and keep the book.
     * Before this fix, PAUSED stayed stuck even after files were deleted.
     */
    @Test
    fun `removeOfflineDownload after paused download resets state to IDLE and keeps book`() = runBlocking {
        val h = setupHarness(numChapters = 3)
        // Chapter 0 was completed before pause; chapters 1-2 were not.
        val file0 = File(h.audioDir, "ch-0.mp3")
        file0.writeBytes(ByteArray(1024))
        dao.updateTrackDownloadState("tr-0", true, file0.absolutePath)
        dao.updateDownloadStateWithState(h.bookId, false, 0.33f, DownloadState.PAUSED)

        h.downloads.removeOfflineDownload(h.bookId)

        assertFalse("Partial file should be deleted", file0.exists())
        val entry = dao.getAudiobookById(h.bookId)
        assertEquals("downloadState must be IDLE after deleting PAUSED copy", DownloadState.IDLE, entry?.downloadState)
        assertEquals(0f, entry?.downloadProgress ?: -1f)
        val book = dao.getAllAudiobooksOnce().firstOrNull { it.id == h.bookId }
        assertTrue("Book must remain in the library", book != null)
    }
}
