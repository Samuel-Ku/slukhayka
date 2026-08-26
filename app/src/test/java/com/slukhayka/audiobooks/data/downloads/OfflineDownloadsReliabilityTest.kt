package com.slukhayka.audiobooks.data.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.testing.FakeFetcher
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
 * Spec-37 T1 (#250) — reliable download with bounded concurrency: chapters are
 * fetched at most three at a time, each chapter is written atomically via a
 * temp file and renamed only after success and size verification, a short body
 * is honestly rejected, unknown length falls back to the minimal-size threshold,
 * and an interrupted temp file is retried on the next run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineDownloadsReliabilityTest {

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
        fetcher: FakeFetcher,
        sourceId: String = "sluhay",
        sourceUrl: String = "https://sluhay.com/svitova-literatura/6177-pasazhir.html"
    ): Triple<LibraryImport, SourceCatalog, OfflineDownloads> {
        val book = SourceBook(title = "Пасажир", author = "Жан-Крістоф Гранже", url = sourceUrl, sourceId = sourceId)
        val adapter = FakeAdapter(sourceId, book, numChapters, streamUrl)
        val imports = LibraryImport(dao, context, listOf(adapter))
        val catalog = SourceCatalog(dao, listOf(adapter), imports)
        // Spec-37 isolates bounded concurrency from spec-38's human rhythm:
        // the injected no-op pause removes the pacing stagger these timing
        // observations depend on (the rhythm itself is pinned in
        // OfflineDownloadsPacingTest).
        val downloads = OfflineDownloads(
            dao, context, catalog, fetcher,
            pauseFor = { }
        )
        return Triple(imports, catalog, downloads)
    }

    @Test
    fun `4read offline download sends the source referer`() = runBlocking {
        val url = "https://s1.reasd.org/5370/01-bunker.mp3"
        val audio = ByteArray(2048) { 0x42 }
        val fetcher = FakeFetcher(sizedStreamResponses = mapOf(url to (audio to audio.size.toLong())))
        val (imports, _, downloads) = harness(
            numChapters = 1,
            streamUrl = { url },
            fetcher = fetcher,
            sourceId = "4read",
            sourceUrl = "https://4read.org/5370-gu-goui-bunker-iluziia.html"
        )
        val bookId = importBook(
            imports,
            "https://4read.org/5370-gu-goui-bunker-iluziia.html",
            sourceId = "4read"
        )

        val result = downloads.downloadAudiobookOffline(bookId)

        assertEquals(1, result.downloadedChapters)
        assertEquals(listOf(mapOf("Referer" to "https://4read.org/")), fetcher.recordedHeaders)
    }

    private suspend fun importBook(
        imports: LibraryImport,
        url: String = "https://sluhay.com/svitova-literatura/6177-pasazhir.html",
        sourceId: String = "sluhay"
    ): String {
        val imported = imports.importFromSourceUrl(sourceId, url)
        assertNotNull(imported)
        return imported!!.id
    }

    @Test
    fun `concurrent streams never exceed three - observable via fake transport counter`() = runBlocking {
        val numChapters = 6
        val urls = (0 until numChapters).map { "https://cdn.example.com/track-$it.mp3" }
        // Distinct content per chapter so hash dedup (T2) does not collapse them.
        val audios = urls.mapIndexed { idx, _ -> ByteArray(2048) { (it + idx).toByte() } }
        val responses = urls.mapIndexed { idx, url -> url to (audios[idx] to audios[idx].size.toLong()) }.toMap()
        // Small delay keeps streams open long enough to observe concurrency.
        val fetcher = FakeFetcher(
            sizedStreamResponses = responses,
            delayMs = 30
        )
        val (imports, _, downloads) = harness(numChapters, { i -> urls[i] }, fetcher)
        val bookId = importBook(imports)

        val result = downloads.downloadAudiobookOffline(bookId)

        assertEquals(numChapters, result.totalChapters)
        assertEquals(numChapters, result.downloadedChapters)
        assertTrue("max concurrent ${fetcher.maxConcurrentStreams} should be <=3", fetcher.maxConcurrentStreams <= 3)
        // With 6 chapters and limit 3, we should have observed exactly 3 at peak.
        assertEquals(3, fetcher.maxConcurrentStreams)
    }

    @Test
    fun `successful chapter exists on disk under target name with no temp leftovers`() = runBlocking {
        val url = "https://cdn.example.com/track-0.mp3"
        val audio = ByteArray(2048) { 0x42 }
        val fetcher = FakeFetcher(sizedStreamResponses = mapOf(url to (audio to audio.size.toLong())))
        val (imports, _, downloads) = harness(1, { url }, fetcher)
        val bookId = importBook(imports)

        val result = downloads.downloadAudiobookOffline(bookId)

        assertEquals(1, result.downloadedChapters)
        val chapterId = dao.getChaptersListForBook(bookId).first().id
        val audioDir = File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR)
        val target = File(audioDir, "$chapterId.mp3")
        val temp = File(audioDir, "$chapterId.mp3.tmp")
        assertTrue("target file should exist", target.exists())
        assertTrue(target.length() > 100)
        assertFalse("temp file should not remain after success", temp.exists())
        val track = dao.getTracksForBookSync(bookId).first()
        assertTrue(track.isDownloaded)
        assertEquals(target.absolutePath, track.localFilePath)
    }

    @Test
    fun `body shorter than declared Content-Length is honestly rejected - no target file`() = runBlocking {
        val url = "https://cdn.example.com/track-0.mp3"
        val actual = ByteArray(500) { 0x42 }
        val declared = 2048L
        val fetcher = FakeFetcher(sizedStreamResponses = mapOf(url to (actual to declared)))
        val (imports, _, downloads) = harness(1, { url }, fetcher)
        val bookId = importBook(imports)

        val result = downloads.downloadAudiobookOffline(bookId)

        assertEquals(1, result.totalChapters)
        assertEquals(0, result.downloadedChapters)
        assertEquals(1, result.failedChapters)
        val chapterId = dao.getChaptersListForBook(bookId).first().id
        val audioDir = File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR)
        val target = File(audioDir, "$chapterId.mp3")
        val temp = File(audioDir, "$chapterId.mp3.tmp")
        assertFalse("target must not exist after short-body rejection", target.exists())
        assertFalse("temp must be cleaned after rejection", temp.exists())
        val track = dao.getTracksForBookSync(bookId).first()
        assertFalse(track.isDownloaded)
        assertNull(track.localFilePath)
        assertFalse(dao.getAudiobookById(bookId)!!.isDownloaded)
    }

    @Test
    fun `unknown Content-Length falls back to minimal size threshold`() = runBlocking {
        // Small body (<100) with unknown length → rejected
        val urlSmall = "https://cdn.example.com/track-small.mp3"
        val small = ByteArray(50) { 0x42 }
        // Large body (>100) with unknown length → accepted
        val urlLarge = "https://cdn.example.com/track-large.mp3"
        val large = ByteArray(500) { 0x42 }

        // First: small body should be rejected
        run {
            val fetcher = FakeFetcher(sizedStreamResponses = mapOf(urlSmall to (small to null)))
            val (imports, _, downloads) = harness(1, { urlSmall }, fetcher)
            val bookId = importBook(imports)
            val result = downloads.downloadAudiobookOffline(bookId)
            assertEquals(0, result.downloadedChapters)
            val chapterId = dao.getChaptersListForBook(bookId).first().id
            val target = File(File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR), "$chapterId.mp3")
            assertFalse(target.exists())
            // Clean for next sub-test
            File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR).deleteRecursively()
            db.clearAllTables()
        }
        // Second: large body should be accepted
        run {
            val fetcher = FakeFetcher(sizedStreamResponses = mapOf(urlLarge to (large to null)))
            val (imports2, _, downloads2) = harness(1, { urlLarge }, fetcher)
            val bookId2 = importBook(imports2)
            val result2 = downloads2.downloadAudiobookOffline(bookId2)
            assertEquals(1, result2.downloadedChapters)
            val chapterId2 = dao.getChaptersListForBook(bookId2).first().id
            val target2 = File(File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR), "$chapterId2.mp3")
            assertTrue(target2.exists())
            assertTrue(target2.length() > 100)
        }
    }

    @Test
    fun `interrupted temp file from previous run is retried on next run`() = runBlocking {
        val url = "https://cdn.example.com/track-0.mp3"
        val audio = ByteArray(2048) { 0x42 }
        val fetcher = FakeFetcher(sizedStreamResponses = mapOf(url to (audio to audio.size.toLong())))
        val (imports, _, downloads) = harness(1, { url }, fetcher)
        val bookId = importBook(imports)
        val chapterId = dao.getChaptersListForBook(bookId).first().id
        val audioDir = File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR)
        audioDir.mkdirs()
        val temp = File(audioDir, "$chapterId.mp3.tmp")
        val target = File(audioDir, "$chapterId.mp3")
        // Simulate interrupted previous run: temp file exists, target does not
        temp.writeBytes(ByteArray(100) { 0x11 })
        assertTrue(temp.exists())
        assertFalse(target.exists())

        val result = downloads.downloadAudiobookOffline(bookId)

        assertEquals(1, result.downloadedChapters)
        assertTrue("target should exist after retry", target.exists())
        assertFalse("temp should be cleaned after success", temp.exists())
        assertTrue(dao.getTracksForBookSync(bookId).first().isDownloaded)
    }

    @Test
    fun `progress updated honestly per chapter and result contains honest counters`() = runBlocking {
        val url0 = "https://cdn.example.com/track-0.mp3"
        val url1 = "https://cdn.example.com/track-1.mp3"
        val audio = ByteArray(2048) { 0x42 }
        // First chapter succeeds, second fails (no entry in fetcher → null stream)
        val fetcher = FakeFetcher(sizedStreamResponses = mapOf(url0 to (audio to audio.size.toLong())))
        val (imports, _, downloads) = harness(2, { i -> if (i == 0) url0 else url1 }, fetcher)
        val bookId = importBook(imports)

        val result = downloads.downloadAudiobookOffline(bookId)

        assertEquals(2, result.totalChapters)
        assertEquals(1, result.downloadedChapters)
        assertEquals(1, result.failedChapters)
        // Progress is success/total when not all succeed
        assertEquals(0.5f, dao.getAudiobookById(bookId)!!.downloadProgress, 0.01f)
        assertFalse(dao.getAudiobookById(bookId)!!.isDownloaded)
        val tracks = dao.getTracksForBookSync(bookId).sortedBy { it.trackIndex }
        assertTrue(tracks[0].isDownloaded)
        assertFalse(tracks[1].isDownloaded)
        assertNull(tracks[1].localFilePath)
    }

    @Test
    fun `old getStream consumers still work without migration`() = runBlocking {
        // Existing consumers use getStream with streamResponses (no sized info).
        // FakeFetcher's getSizedStream should still serve those bytes with
        // length = bytes.size, so download succeeds.
        val url = "https://cdn.example.com/track-0.mp3"
        val audio = ByteArray(2048) { 0x42 }
        val fetcher = FakeFetcher(streamResponses = mapOf(url to audio))
        // Verify old method still works
        val stream = fetcher.getStream(url)
        assertNotNull(stream)
        assertEquals(audio.size, stream!!.readBytes().size)

        val (imports, _, downloads) = harness(1, { url }, fetcher)
        val bookId = importBook(imports)
        val result = downloads.downloadAudiobookOffline(bookId)
        assertEquals(1, result.downloadedChapters)
    }
}
