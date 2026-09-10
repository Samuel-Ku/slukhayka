package com.slukhayka.audiobooks.data.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #387 — a chapter download that dies mid-stream continues from the kept
 * partial on retry instead of starting from zero. The partial is keyed by
 * chapter AND track url (a re-resolved different url never resumes), a 206
 * resumes only on an exact slice start, and anything else restarts fresh
 * or fails with the partial kept — never silently spliced audio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DownloadRangeResumeTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private val dao get() = db.audiobookDao()
    private val audioDir: File get() = File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioDir.deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        audioDir.deleteRecursively()
        db.close()
    }

    private class FakeChapterAdapter(
        override val sourceId: String,
        private val book: SourceBook,
    ) : SourceAdapter {
        override val sessionBound: Boolean get() = false
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = listOf(book)
        override suspend fun fetchBookPage(url: String): SourceBookDetail = SourceBookDetail(
            title = book.title,
            author = book.author,
            url = url,
            chapters = listOf(SourceChapter(title = "${book.title} 1", streamUrl = book.url)),
        )
    }

    /** Scripted transport: fresh bodies die or serve whole, Range answers per mode. */
    private class ScriptedFetcher(
        val full: ByteArray,
        var freshDiesAfter: Int = -1,
        var rangeMode: RangeMode = RangeMode.SLICE,
    ) : HttpFetcher() {
        enum class RangeMode { SLICE, FULL_200, MISMATCH, NULL }

        val rangeHeaders = CopyOnWriteArrayList<Map<String, String>>()
        var sizedCalls = 0

        private fun dyingStream(dieAfter: Int): InputStream {
            var delivered = 0
            return object : ByteArrayInputStream(full) {
                // Capped chunks: one bulk read must not deliver the whole
                // body at once, or the abort below never fires.
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (delivered >= dieAfter) throw IOException("Software caused connection abort")
                    val n = super.read(b, off, minOf(len, 512))
                    if (n > 0) delivered += n
                    return n
                }

                override fun read(): Int {
                    if (delivered >= dieAfter) throw IOException("Software caused connection abort")
                    val n = super.read()
                    if (n >= 0) delivered++
                    return n
                }
            }
        }

        override fun getSizedStreamResult(url: String, extraHeaders: Map<String, String>): SizedStreamResult {
            sizedCalls++
            val stream = if (freshDiesAfter in 1 until full.size) {
                dyingStream(freshDiesAfter)
            } else {
                ByteArrayInputStream(full)
            }
            return SizedStreamResult(200, SizedStream(stream, full.size.toLong()))
        }

        override fun getRangeStream(url: String, extraHeaders: Map<String, String>): RangeResponse? {
            rangeHeaders += extraHeaders
            return when (rangeMode) {
                RangeMode.NULL -> null
                RangeMode.FULL_200 -> RangeResponse(
                    ByteArrayInputStream(full), 200, full.size.toLong(), null, "audio/mpeg"
                )
                RangeMode.MISMATCH -> RangeResponse(
                    ByteArrayInputStream(full), 206, full.size.toLong(),
                    "bytes 0-${full.size - 1}/${full.size}", "audio/mpeg"
                )
                RangeMode.SLICE -> {
                    val start = extraHeaders["Range"]
                        ?.substringAfter("bytes=")?.substringBefore("-")?.toIntOrNull()
                        ?: return null
                    if (start <= 0 || start >= full.size) return null
                    val slice = full.copyOfRange(start, full.size)
                    RangeResponse(
                        ByteArrayInputStream(slice), 206, slice.size.toLong(),
                        "bytes $start-${full.size - 1}/${full.size}", "audio/mpeg"
                    )
                }
            }
        }
    }

    private fun harness(fetcher: HttpFetcher): Triple<LibraryImport, SourceCatalog, OfflineDownloads> {
        val book = SourceBook(
            title = "Книга",
            author = "Автор",
            url = "https://sluhay.com.ua/book",
            sourceId = "sluhayua",
        )
        val adapter = FakeChapterAdapter("sluhayua", book)
        val imports = LibraryImport(dao, context, listOf(adapter))
        val catalog = SourceCatalog(dao, listOf(adapter), imports)
        val downloads = OfflineDownloads(dao, context, catalog, fetcher, pauseFor = { })
        return Triple(imports, catalog, downloads)
    }

    private suspend fun importOne(imports: LibraryImport): String {
        val imported = imports.importFromSourceUrl("sluhayua", "https://sluhay.com.ua/book")
        return imported!!.id
    }

    private suspend fun chapterAndTrack(bookId: String): Pair<String, String> {
        val chapterId = dao.getChaptersListForBook(bookId).first().id
        val trackUrl = dao.getTracksForBookSync(bookId).first().url
        return chapterId to trackUrl
    }

    @Test
    fun `mid-stream abort resumes from the kept partial on retry - the issue scene`() = runBlocking {
        // Realistic scale (the issue's 80 MB chapter): the partial must
        // exceed the 64 KB copy buffers, or nothing ever reaches the disk.
        val full = ByteArray(200_000) { it.toByte() }
        val fetcher = ScriptedFetcher(full, freshDiesAfter = 100_000)
        val (imports, _, downloads) = harness(fetcher)
        val bookId = importOne(imports)
        val (chapterId, trackUrl) = chapterAndTrack(bookId)
        val resumeName = DownloadResumePolicy.resumeTempName(chapterId, trackUrl)

        // First run dies mid-stream like the 80 MB YouTube chapter did.
        val first = downloads.downloadAudiobookOffline(bookId)
        val dirState = audioDir.listFiles()?.joinToString { it.name + ":" + it.length() }
        assertEquals("total=${first.totalChapters} sized=${fetcher.sizedCalls} dir=[$dirState]", 0, first.downloadedChapters)
        val kept = File(audioDir, resumeName)
        assertTrue("the partial is kept, not deleted", kept.exists())
        val keptBytes = kept.length()
        // Whatever flushed before the abort (buffering keeps some bytes
        // in flight) — the assertion that matters is the exact reassembly
        // below; the kept prefix only decides the Range offset, read back
        // dynamically.
        assertTrue("partial holds real bytes, got $keptBytes", keptBytes in 1 until full.size)

        // Second run resumes: one Range request, append, verified complete.
        val second = downloads.downloadAudiobookOffline(bookId)
        assertEquals(1, second.downloadedChapters)
        assertTrue(fetcher.rangeHeaders.any { it["Range"] == "bytes=$keptBytes-" })
        val target = File(audioDir, "$chapterId.mp3")
        assertTrue(target.exists())
        assertArrayEquals("prefix from run one + slice from run two", full, target.readBytes())
        assertTrue("no resume temp left behind", audioDir.listFiles()?.none { it.name.endsWith(".mp3.resume") } ?: true)
    }

    @Test
    fun `server ignoring range restarts fresh`() = runBlocking {
        val full = ByteArray(2048) { it.toByte() }
        val fetcher = ScriptedFetcher(full, rangeMode = ScriptedFetcher.RangeMode.FULL_200)
        val (imports, _, downloads) = harness(fetcher)
        val bookId = importOne(imports)
        val (chapterId, trackUrl) = chapterAndTrack(bookId)
        File(audioDir, DownloadResumePolicy.resumeTempName(chapterId, trackUrl))
            .also { audioDir.mkdirs() }
            .writeBytes(full.copyOfRange(0, 512))

        val result = downloads.downloadAudiobookOffline(bookId)
        assertEquals(1, result.downloadedChapters)
        assertArrayEquals(full, File(audioDir, "$chapterId.mp3").readBytes())
    }

    @Test
    fun `transport failure keeps the partial and spends no fresh request`() = runBlocking {
        val full = ByteArray(2048) { it.toByte() }
        val fetcher = ScriptedFetcher(full, rangeMode = ScriptedFetcher.RangeMode.NULL)
        val (imports, _, downloads) = harness(fetcher)
        val bookId = importOne(imports)
        val (chapterId, trackUrl) = chapterAndTrack(bookId)
        val resumeFile = File(audioDir, DownloadResumePolicy.resumeTempName(chapterId, trackUrl))
            .also { audioDir.mkdirs() }
        resumeFile.writeBytes(full.copyOfRange(0, 512))

        val result = downloads.downloadAudiobookOffline(bookId)
        assertEquals(0, result.downloadedChapters)
        assertEquals("partial kept for the next run", 512, resumeFile.length())
        assertEquals("no wasted fresh request on a dead transport", 0, fetcher.sizedCalls)
    }

    @Test
    fun `mismatched slice restarts fresh`() = runBlocking {
        val full = ByteArray(2048) { it.toByte() }
        val fetcher = ScriptedFetcher(full, rangeMode = ScriptedFetcher.RangeMode.MISMATCH)
        val (imports, _, downloads) = harness(fetcher)
        val bookId = importOne(imports)
        val (chapterId, trackUrl) = chapterAndTrack(bookId)
        File(audioDir, DownloadResumePolicy.resumeTempName(chapterId, trackUrl))
            .also { audioDir.mkdirs() }
            .writeBytes(full.copyOfRange(0, 512))

        val result = downloads.downloadAudiobookOffline(bookId)
        assertEquals(1, result.downloadedChapters)
        assertArrayEquals(full, File(audioDir, "$chapterId.mp3").readBytes())
    }

    @Test
    fun `re-resolved different url never resumes the old partial`() = runBlocking {
        val full = ByteArray(2048) { it.toByte() }
        val fetcher = ScriptedFetcher(full)
        val (imports, _, downloads) = harness(fetcher)
        val bookId = importOne(imports)
        val (chapterId, _) = chapterAndTrack(bookId)
        // A stale partial from a previous (different) track url.
        val stale = File(audioDir, DownloadResumePolicy.resumeTempName(chapterId, "https://old.example/a.mp3"))
            .also { audioDir.mkdirs() }
        stale.writeBytes(ByteArray(512) { 0x7F })

        val result = downloads.downloadAudiobookOffline(bookId)
        assertEquals(1, result.downloadedChapters)
        assertTrue("no Range without an adopted partial", fetcher.rangeHeaders.isEmpty())
        assertArrayEquals(full, File(audioDir, "$chapterId.mp3").readBytes())
        assertTrue("stale key cleaned on completion", !stale.exists())
    }
}
