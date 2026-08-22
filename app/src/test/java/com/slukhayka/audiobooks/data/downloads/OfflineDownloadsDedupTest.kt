package com.slukhayka.audiobooks.data.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.contentHashOf
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.testing.FakeFetcher
import com.slukhayka.audiobooks.ui.library.OutcomeMessages
import java.io.ByteArrayInputStream
import java.io.File
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

/**
 * Spec-37 T2 (#251) — one book one copy on disk: SHA-256, URL reuse, hash
 * sharing, deletion with ref-counting, clear cache and UI messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineDownloadsDedupTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private val dao get() = db.audiobookDao()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR).deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR).deleteRecursively()
        db.close()
    }

    private class FakeAdapter(
        override val sourceId: String,
        private val book: SourceBook,
        private val numChapters: Int = 2,
        private val streamUrl: (Int) -> String = { "chapter-$it" }
    ) : SourceAdapter {
        override val sessionBound: Boolean get() = false
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = listOf(book)
        override suspend fun fetchBookPage(url: String): SourceBookDetail = SourceBookDetail(
            title = book.title,
            author = book.author,
            url = url,
            chapters = (0 until numChapters).map { i ->
                SourceChapter(title = "${book.title} ${i + 1}", streamUrl = streamUrl(i))
            }
        )
    }

    private fun harness(
        numChapters: Int,
        streamUrl: (Int) -> String,
        fetcher: FakeFetcher
    ): Triple<LibraryImport, SourceCatalog, OfflineDownloads> {
        val book = SourceBook(title = "Пасажир", author = "Жан-Крістоф Гранже", url = "https://sluhay.com/svitova-literatura/6177-pasazhir.html", sourceId = "sluhay")
        val adapter = FakeAdapter("sluhay", book, numChapters, streamUrl)
        val imports = LibraryImport(dao, context, listOf(adapter))
        val catalog = SourceCatalog(dao, listOf(adapter), imports)
        val downloads = OfflineDownloads(dao, context, catalog, fetcher)
        return Triple(imports, catalog, downloads)
    }

    private suspend fun importBook(
        imports: LibraryImport,
        url: String = "https://sluhay.com/svitova-literatura/6177-pasazhir.html"
    ): String {
        val imported = imports.importFromSourceUrl("sluhay", url)
        assertNotNull(imported)
        return imported!!.id
    }

    @Test
    fun `after download track rows carry SHA-256 of downloaded files`() = runBlocking {
        val url = "https://cdn.example.com/track-0.mp3"
        val audio = ByteArray(2048) { 0x42 }
        val expectedHash = contentHashOf(ByteArrayInputStream(audio))
        val fetcher = FakeFetcher(sizedStreamResponses = mapOf(url to (audio to audio.size.toLong())))
        val (imports, _, downloads) = harness(1, { url }, fetcher)
        val bookId = importBook(imports)

        downloads.downloadAudiobookOffline(bookId)

        val track = dao.getTracksForBookSync(bookId).first()
        assertEquals(expectedHash, track.contentHash)
        assertTrue(track.isDownloaded)
        assertNotNull(track.localFilePath)
    }

    @Test
    fun `chapter with already downloaded same URL does not generate new network request`() = runBlocking {
        val url = "https://cdn.example.com/track-0.mp3"
        val audio = ByteArray(2048) { 0x42 }
        // First book downloads the file
        val fetcher1 = FakeFetcher(sizedStreamResponses = mapOf(url to (audio to audio.size.toLong())))
        val (imports1, _, downloads1) = harness(1, { url }, fetcher1)
        val bookId1 = importBook(imports1)
        val result1 = downloads1.downloadAudiobookOffline(bookId1)
        assertEquals(1, result1.downloadedChapters)
        assertEquals(1, fetcher1.recordedHeaders.size)

        // Second book with same URL but different bookId — should reuse without network
        // We need to create a second book with same URL but different work.
        // Use a different sourceId/book to avoid same bookId.
        val book2 = SourceBook(title = "Пасажир дубль", author = "Жан-Крістоф Гранже", url = "https://sluhay.com/svitova-literatura/6177-pasazhir-2.html", sourceId = "sluhay")
        val adapter2 = FakeAdapter("sluhay", book2, 1) { url }
        val imports2 = LibraryImport(dao, context, listOf(adapter2))
        val catalog2 = SourceCatalog(dao, listOf(adapter2), imports2)
        val fetcher2 = FakeFetcher(sizedStreamResponses = mapOf(url to (audio to audio.size.toLong())))
        // Import second book (different URL, but chapter stream URL same)
        val imported2 = imports2.importFromSourceUrl("sluhay", book2.url)!!
        val downloads2 = OfflineDownloads(dao, context, catalog2, fetcher2)
        val result2 = downloads2.downloadAudiobookOffline(imported2.id)

        // No network request for the reused chapter
        assertEquals(0, fetcher2.recordedHeaders.size)
        assertEquals(1, result2.totalChapters)
        // Reused chapter should be counted as reused/shared, not as newly downloaded
        assertTrue(result2.reusedChapters == 1 || result2.sharedChapters == 1)
        assertEquals(0, result2.downloadedChapters)
        // Both tracks should point to same file
        val track1 = dao.getTracksForBookSync(bookId1).first()
        val track2 = dao.getTracksForBookSync(imported2.id).first()
        assertEquals(track1.localFilePath, track2.localFilePath)
        assertEquals(track1.contentHash, track2.contentHash)
    }

    private suspend fun createDirectBook(
        bookId: String,
        title: String,
        author: String,
        trackUrls: List<String>
    ) {
        // Work and LibraryEntry
        val mergeKey = com.slukhayka.audiobooks.data.merge.MergeKey.keyFor(title, author)
        val workId = if (mergeKey.isNotBlank()) mergeKey else bookId
        if (mergeKey.isNotBlank() && dao.findWorkByMergeKey(mergeKey) == null) {
            dao.upsertWork(
                com.slukhayka.audiobooks.data.db.WorkEntity(
                    id = workId,
                    mergeKey = mergeKey,
                    title = title,
                    author = author
                )
            )
        }
        dao.upsertLibraryEntry(id = bookId, workId = workId, isFavorite = false, createdAt = System.currentTimeMillis(), downloadProgress = 0f)
        val audiobook = com.slukhayka.audiobooks.data.db.AudiobookEntity(
            id = bookId,
            title = title,
            author = author,
            narrator = "Тестовий диктор",
            description = "test",
            coverDrawableRes = 0,
            genre = "test",
            sourceUrl = "https://sluhay.com/$bookId.html",
            isDownloaded = false,
            totalChapters = trackUrls.size,
            totalDurationSeconds = 0L
        )
        dao.insertAudiobooks(listOf(audiobook))
        val narrator = "Тестовий диктор"
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook(mergeKey, bookId, narrator)
        dao.insertEdition(
            com.slukhayka.audiobooks.data.db.EditionEntity(
                id = editionId,
                workId = bookId,
                narrator = narrator,
                totalChapters = trackUrls.size,
                totalDurationSeconds = 0L
            )
        )
        val sourceId = "sluhay-$bookId"
        dao.insertSources(
            listOf(
                com.slukhayka.audiobooks.data.db.SourceEntity(
                    id = sourceId,
                    bookId = bookId,
                    editionId = editionId,
                    type = "sluhay",
                    url = "https://sluhay.com/$bookId.html"
                )
            )
        )
        val chapters = trackUrls.mapIndexed { idx, _ ->
            com.slukhayka.audiobooks.data.db.ChapterEntity(
                id = "${bookId}_ch_${idx}",
                bookId = bookId,
                chapterIndex = idx,
                title = "Глава ${idx + 1}",
                durationSeconds = 0L,
                editionId = editionId
            )
        }
        dao.insertChapters(chapters)
        val tracks = trackUrls.mapIndexed { idx, url ->
            com.slukhayka.audiobooks.data.db.SourceTrackEntity(
                id = "${sourceId}_tr_${idx}",
                sourceId = sourceId,
                trackIndex = idx,
                url = url
            )
        }
        dao.insertTracks(tracks)
    }

    @Test
    fun `two cards of same rendition after download share one file on disk`() = runBlocking {
        val url = "https://cdn.example.com/track-0.mp3"
        val url2 = "https://cdn.example.com/track-0-copy.mp3"
        val audio = ByteArray(2048) { 0xAB.toByte() }
        // First card via normal import
        val fetcher1 = FakeFetcher(sizedStreamResponses = mapOf(url to (audio to audio.size.toLong())))
        val (imports1, catalog1, downloads1) = harness(1, { url }, fetcher1)
        val bookId1 = importBook(imports1)
        downloads1.downloadAudiobookOffline(bookId1)

        // Second card of same rendition created directly with different bookId but same Work
        val bookId2 = "test-book-2-same-rendition"
        createDirectBook(bookId2, "Пасажир", "Жан-Крістоф Гранже", listOf(url2))
        val fetcher2 = FakeFetcher(sizedStreamResponses = mapOf(url2 to (audio to audio.size.toLong())))
        val catalog2 = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))
        val downloads2 = OfflineDownloads(dao, context, catalog2, fetcher2)
        val result2 = downloads2.downloadAudiobookOffline(bookId2)

        // Even though URLs differ, same bytes => same hash => should share file
        val track1 = dao.getTracksForBookSync(bookId1).first()
        val track2 = dao.getTracksForBookSync(bookId2).first()
        assertEquals(track1.contentHash, track2.contentHash)
        assertEquals(track1.localFilePath, track2.localFilePath)
        // Only one file on disk
        val audioDir = File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR)
        val files = audioDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") } ?: emptyList()
        assertEquals(1, files.size)
        // Result should report shared
        assertTrue(result2.sharedChapters == 1 || result2.reusedChapters == 1)
        assertEquals(audio.size.toLong(), files.first().length())
    }

    @Test
    fun `different audio of different renditions of same work is NOT glued`() = runBlocking {
        val url1 = "https://cdn.example.com/track-rendition-1.mp3"
        val url2 = "https://cdn.example.com/track-rendition-2.mp3"
        val audio1 = ByteArray(2048) { 0x11 }
        val audio2 = ByteArray(2048) { 0x22 } // different content
        val hash1 = contentHashOf(ByteArrayInputStream(audio1))
        val hash2 = contentHashOf(ByteArrayInputStream(audio2))
        assertTrue(hash1 != hash2)

        val fetcher1 = FakeFetcher(sizedStreamResponses = mapOf(url1 to (audio1 to audio1.size.toLong())))
        val (imports1, _, downloads1) = harness(1, { url1 }, fetcher1)
        val bookId1 = importBook(imports1)
        downloads1.downloadAudiobookOffline(bookId1)

        val bookId2 = "test-book-2-different-rendition"
        createDirectBook(bookId2, "Пасажир", "Жан-Крістоф Гранже", listOf(url2))
        val fetcher2 = FakeFetcher(sizedStreamResponses = mapOf(url2 to (audio2 to audio2.size.toLong())))
        val catalog2 = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))
        val downloads2 = OfflineDownloads(dao, context, catalog2, fetcher2)
        downloads2.downloadAudiobookOffline(bookId2)

        val track1 = dao.getTracksForBookSync(bookId1).first()
        val track2 = dao.getTracksForBookSync(bookId2).first()
        assertTrue(track1.contentHash != track2.contentHash)
        assertTrue(track1.localFilePath != track2.localFilePath)
        val audioDir = File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR)
        val files = audioDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") } ?: emptyList()
        assertEquals(2, files.size)
    }

    @Test
    fun `deletion of one of two shared books leaves file and second book playable`() = runBlocking {
        val url = "https://cdn.example.com/track-0.mp3"
        val audio = ByteArray(2048) { 0x33 }
        val fetcher1 = FakeFetcher(sizedStreamResponses = mapOf(url to (audio to audio.size.toLong())))
        val (imports1, _, downloads1) = harness(1, { url }, fetcher1)
        val bookId1 = importBook(imports1)
        downloads1.downloadAudiobookOffline(bookId1)

        val book2 = SourceBook(title = "Пасажир дубль", author = "Жан-Крістоф Гранже", url = "https://sluhay.com/svitova-literatura/6177-pasazhir-2.html", sourceId = "sluhay")
        val adapter2 = FakeAdapter("sluhay", book2, 1) { url }
        val imports2 = LibraryImport(dao, context, listOf(adapter2))
        val catalog2 = SourceCatalog(dao, listOf(adapter2), imports2)
        val fetcher2 = FakeFetcher(sizedStreamResponses = mapOf(url to (audio to audio.size.toLong())))
        val imported2 = imports2.importFromSourceUrl("sluhay", book2.url)!!
        val downloads2 = OfflineDownloads(dao, context, catalog2, fetcher2)
        downloads2.downloadAudiobookOffline(imported2.id)

        val track1Before = dao.getTracksForBookSync(bookId1).first()
        val track2Before = dao.getTracksForBookSync(imported2.id).first()
        val sharedPath = track1Before.localFilePath!!
        assertEquals(sharedPath, track2Before.localFilePath)
        assertTrue(File(sharedPath).exists())

        // Delete first book's download
        downloads2.removeOfflineDownload(bookId1)

        // File should still exist, second book still playable
        assertTrue(File(sharedPath).exists())
        val track2After = dao.getTracksForBookSync(imported2.id).first()
        assertTrue(track2After.isDownloaded)
        assertEquals(sharedPath, track2After.localFilePath)
        // First book's track should be cleared
        val track1After = dao.getTracksForBookSync(bookId1).first()
        assertFalse(track1After.isDownloaded)
        assertNull(track1After.localFilePath)

        // Delete second (last) reference — file should be gone
        downloads2.removeOfflineDownload(imported2.id)
        assertFalse(File(sharedPath).exists())
        val track2Final = dao.getTracksForBookSync(imported2.id).first()
        assertFalse(track2Final.isDownloaded)
    }

    @Test
    fun `clear cache resets all rows and files as before`() = runBlocking {
        val url = "https://cdn.example.com/track-0.mp3"
        val audio = ByteArray(2048) { 0x44 }
        val fetcher = FakeFetcher(sizedStreamResponses = mapOf(url to (audio to audio.size.toLong())))
        val (imports, _, downloads) = harness(1, { url }, fetcher)
        val bookId = importBook(imports)
        downloads.downloadAudiobookOffline(bookId)

        val audioDir = File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR)
        assertTrue(audioDir.listFiles()?.isNotEmpty() == true)

        downloads.clearAllAudioCache()

        assertFalse(audioDir.exists() && (audioDir.listFiles()?.isNotEmpty() == true))
        assertTrue(dao.getTracksForBookSync(bookId).none { it.isDownloaded })
        assertTrue(dao.getTracksForBookSync(bookId).all { it.localFilePath == null })
        assertFalse(dao.getAudiobookById(bookId)!!.isDownloaded)
    }

    @Test
    fun `stream-only refusal works as before`() = runBlocking {
        // lihtar is stream-only per DownloadPolicy
        val lihtarBook = SourceBook(title = "Пасажир", author = "Автор", url = "https://lihtar.in.ua/biblioteka/kniga", sourceId = "lihtar")
        val adapter = FakeAdapter("lihtar", lihtarBook, 1) { "https://cdn.example.com/track.mp3" }
        val imports = LibraryImport(dao, context, listOf(adapter))
        val catalog = SourceCatalog(dao, listOf(adapter), imports)
        val fetcher = FakeFetcher(sizedStreamResponses = mapOf("https://cdn.example.com/track.mp3" to (ByteArray(2048) { 0x55 } to 2048L)))
        val downloads = OfflineDownloads(dao, context, catalog, fetcher)
        val imported = imports.importFromSourceUrl("lihtar", lihtarBook.url)!!
        val result = downloads.downloadAudiobookOffline(imported.id)

        assertEquals(0, result.totalChapters)
        assertEquals(0, result.downloadedChapters)
        assertFalse(dao.getAudiobookById(imported.id)!!.isDownloaded)
    }

    @Test
    fun `UI messages built from honest counters via pure module`() {
        val ok = OfflineDownloads.OfflineDownloadResult(downloadedChapters = 2, totalChapters = 2, sharedChapters = 1)
        val partial = OfflineDownloads.OfflineDownloadResult(downloadedChapters = 1, totalChapters = 2, sharedChapters = 0, reusedChapters = 1)
        val failed = OfflineDownloads.OfflineDownloadResult(downloadedChapters = 0, totalChapters = 2)

        assertTrue(OutcomeMessages.downloadOutcome(ok).contains("завантажено"))
        assertTrue(OutcomeMessages.downloadOutcome(partial).contains("1"))
        assertTrue(OutcomeMessages.downloadOutcome(failed).contains("Не вдалося"))
        // Shared count should appear in message when >0
        val withShared = OfflineDownloads.OfflineDownloadResult(downloadedChapters = 1, totalChapters = 2, sharedChapters = 1)
        assertTrue(OutcomeMessages.downloadOutcome(withShared).contains("спільно"))
    }
}
