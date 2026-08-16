package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog.PlayableChapter
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.duration.ChapterDurationProbe
import com.slukhayka.audiobooks.data.duration.MpegAudioFrame
import com.slukhayka.audiobooks.data.duration.ProbeResult
import com.slukhayka.audiobooks.data.duration.StreamProber
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
 * spec-24 T8 (#169) — the background chapter-duration probing pass, through
 * the same seams it runs on in production: Room chapters + a fake
 * chapter→track pairing (the catalog seam's shape) + a fake stream prober
 * (prior art: DurationEnrichmentRepositoryTest). Unknown-duration chapters
 * receive size × 8 / bitrate from the prober; local books are never probed;
 * a failing probe leaves the row untouched and never aborts the batch; the
 * book total becomes the chapter sum only when it was 0 and every chapter is
 * known; the pass respects its batch limit and throttles itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChapterDurationProbeRepositoryTest {

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

    /** A 128 kbps CBR frame header — duration = size × 8 / 128000. */
    private val cbr128 = MpegAudioFrame.Header(bitrateKbps = 128, sampleRateHz = 44_100)

    /**
     * Records which urls were probed; returns the configured [ProbeResult],
     * null for unconfigured urls — the failing-probe case.
     */
    private class FakeProber(
        private val resultFor: Map<String, ProbeResult?>
    ) : StreamProber {
        val probedUrls = mutableListOf<String>()
        override suspend fun probe(url: String): ProbeResult? {
            probedUrls += url
            return resultFor[url]
        }
    }

    private fun book(id: String, url: String = "https://provider.example/$id.mp3") =
        AudiobookEntity(
            id = id,
            title = "Книга $id",
            author = "Автор",
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            sourceUrl = url,
            genre = "",
            totalDurationSeconds = 0L
        )

    private fun chapter(bookId: String, index: Int, durationSeconds: Long) = ChapterEntity(
        id = "$bookId-ch$index",
        bookId = bookId,
        chapterIndex = index,
        title = "Розділ ${index + 1}",
        durationSeconds = durationSeconds
    )

    private fun track(bookId: String, index: Int, url: String) = SourceTrackEntity(
        id = "$bookId-tr$index",
        sourceId = "$bookId-src",
        trackIndex = index,
        url = url
    )

    /** Pairs each chapter with a track of the same index — the catalog seam's shape. */
    private fun pairs(bookId: String, tracks: Map<Int, SourceTrackEntity>): List<PlayableChapter> {
        val chapters = runBlocking { dao.getChaptersListForBook(bookId) }
        return chapters.map { ch ->
            PlayableChapter(chapter = ch, track = tracks[ch.chapterIndex])
        }
    }

    private fun seed(books: List<AudiobookEntity>, chapters: List<ChapterEntity>) = runBlocking {
        dao.insertAudiobooks(books)
        dao.insertChapters(chapters)
    }

    /** One pass instance — the CAS throttle lives on it, so reuse matters. */
    private fun pass(prober: FakeProber, tracks: Map<String, Map<Int, SourceTrackEntity>> = emptyMap()): ChapterDurationProbe {
        val provider: suspend (String) -> List<PlayableChapter> = { bookId -> pairs(bookId, tracks[bookId].orEmpty()) }
        return ChapterDurationProbe(dao, provider, prober)
    }

    private fun run(pass: ChapterDurationProbe, batchLimit: Int = 10, now: Long = START_EPOCH): Int =
        runBlocking { pass.probeUnknownChapters(batchLimit = batchLimit, now = { now }) }

    private fun durationOf(chapterId: String): Long = runBlocking {
        dao.getChaptersListForBook(chapterId.substringBefore("-ch")).first { it.id == chapterId }.durationSeconds
    }

    private fun bookDurationOf(bookId: String): Long = runBlocking { dao.getAudiobookById(bookId) }!!.totalDurationSeconds

    // ---------------------------------------------------------------------
    // Writes size × 8 / bitrate through the chapter-duration seam
    // ---------------------------------------------------------------------

    @Test
    fun `unknown chapters receive size times 8 over bitrate from the probe`() {
        val tracks = mapOf(
            "a" to mapOf(1 to track("a", 1, "https://provider.example/a/2.mp3"))
        )
        val prober = FakeProber(
            mapOf("https://provider.example/a/2.mp3" to ProbeResult(contentLength = 4_410_000, frame = cbr128))
        )
        // 4_410_000 × 8 / 128_000 = 275.6 → 275 s.
        seed(listOf(book("a")), listOf(chapter("a", 0, 600L), chapter("a", 1, 0L)))
        val pass = pass(prober, tracks)

        assertEquals(1, run(pass))
        assertEquals(600L, durationOf("a-ch0")) // known chapter untouched
        assertEquals(275L, durationOf("a-ch1"))
        assertEquals(listOf("https://provider.example/a/2.mp3"), prober.probedUrls)
    }

    @Test
    fun `a chapter without a paired track is never probed`() {
        val prober = FakeProber(emptyMap()) // every probe fails
        seed(listOf(book("a")), listOf(chapter("a", 0, 0L), chapter("a", 1, 0L)))
        val pass = pass(prober, mapOf("a" to mapOf(0 to track("a", 0, "https://provider.example/a/1.mp3"))))

        assertEquals(0, run(pass))
        assertEquals(0L, durationOf("a-ch0")) // probed but failed → untouched
        assertEquals(0L, durationOf("a-ch1")) // trackless → never reaches the prober
        assertEquals(listOf("https://provider.example/a/1.mp3"), prober.probedUrls)
    }

    // ---------------------------------------------------------------------
    // Local books are skipped
    // ---------------------------------------------------------------------

    @Test
    fun `local books with a blank source url are never probed`() {
        val local = book("local", url = "")
        val prober = FakeProber(emptyMap())
        seed(listOf(local), listOf(chapter("local", 0, 0L)))

        assertEquals(0, run(pass(prober)))
        assertTrue(prober.probedUrls.isEmpty())
        assertEquals(0L, durationOf("local-ch0"))
    }

    // ---------------------------------------------------------------------
    // Failure isolation and honest writes
    // ---------------------------------------------------------------------

    @Test
    fun `a failing probe leaves the row untouched and does not abort the batch`() {
        val tracks = mapOf(
            "fail" to mapOf(0 to track("fail", 0, "https://provider.example/fail/1.mp3")),
            "ok" to mapOf(0 to track("ok", 0, "https://provider.example/ok/1.mp3"))
        )
        val prober = FakeProber(
            mapOf("https://provider.example/ok/1.mp3" to ProbeResult(contentLength = 4_410_000, frame = cbr128))
        )
        seed(listOf(book("fail"), book("ok")), listOf(chapter("fail", 0, 0L), chapter("ok", 0, 0L)))

        assertEquals(1, run(pass(prober, tracks)))
        assertEquals(0L, durationOf("fail-ch0")) // failed probe untouched
        assertEquals(275L, durationOf("ok-ch0"))
    }

    // ---------------------------------------------------------------------
    // Book total: chapter sum only when it was 0 and every chapter is known
    // ---------------------------------------------------------------------

    @Test
    fun `book total becomes the chapter sum when it was zero and all chapters are known`() {
        val tracks = mapOf("a" to mapOf(1 to track("a", 1, "https://provider.example/a/2.mp3")))
        val prober = FakeProber(
            mapOf("https://provider.example/a/2.mp3" to ProbeResult(contentLength = 4_410_000, frame = cbr128))
        )
        seed(listOf(book("a")), listOf(chapter("a", 0, 600L), chapter("a", 1, 0L)))

        assertEquals(1, run(pass(prober, tracks)))
        // 600 + 275 = 875, totalChapters set to the real chapter count.
        assertEquals(875L, bookDurationOf("a"))
    }

    @Test
    fun `a site-provided book total is never overwritten by the chapter sum`() {
        val siteTotal = book("a", url = "https://provider.example/a.mp3").copy(totalDurationSeconds = 5000L)
        val tracks = mapOf("a" to mapOf(1 to track("a", 1, "https://provider.example/a/2.mp3")))
        val prober = FakeProber(
            mapOf("https://provider.example/a/2.mp3" to ProbeResult(contentLength = 4_410_000, frame = cbr128))
        )
        seed(listOf(siteTotal), listOf(chapter("a", 0, 600L), chapter("a", 1, 0L)))

        assertEquals(1, run(pass(prober, tracks)))
        assertEquals(275L, durationOf("a-ch1")) // the chapter is still filled
        assertEquals(5000L, bookDurationOf("a")) // the total stays site-provided
    }

    @Test
    fun `book total stays zero while any chapter is still unknown`() {
        val tracks = mapOf("a" to mapOf(0 to track("a", 0, "https://provider.example/a/1.mp3")))
        val prober = FakeProber(emptyMap()) // the only track fails
        seed(listOf(book("a")), listOf(chapter("a", 0, 0L), chapter("a", 1, 0L)))

        assertEquals(0, run(pass(prober, tracks)))
        assertEquals(0L, bookDurationOf("a"))
    }

    // ---------------------------------------------------------------------
    // Bounded batch and throttle
    // ---------------------------------------------------------------------

    @Test
    fun `the pass respects its batch limit`() {
        val tracks = (1..5).associate { id ->
            "b$id" to mapOf(0 to track("b$id", 0, "https://provider.example/b$id/1.mp3"))
        }
        val prober = FakeProber(
            (1..5).associate { "https://provider.example/b$it/1.mp3" to ProbeResult(4_410_000L, cbr128) }
        )
        seed((1..5).map { book("b$it") }, (1..5).map { chapter("b$it", 0, 0L) })

        assertEquals(3, run(pass(prober, tracks), batchLimit = 3))
        assertEquals(3, prober.probedUrls.size)
    }

    @Test
    fun `the pass throttles itself between runs`() {
        val tracks = (1..4).associate { id ->
            "c$id" to mapOf(0 to track("c$id", 0, "https://provider.example/c$id/1.mp3"))
        }
        val prober = FakeProber(
            (1..4).associate { "https://provider.example/c$it/1.mp3" to ProbeResult(4_410_000L, cbr128) }
        )
        val interval = ChapterDurationProbe.MIN_PROBE_INTERVAL_MS
        seed((1..4).map { book("c$it") }, (1..4).map { chapter("c$it", 0, 0L) })
        val pass = pass(prober, tracks)

        // First run: probes the first two (batch limit 2).
        assertEquals(2, run(pass, batchLimit = 2, now = START_EPOCH))
        // Second run within the interval: throttled to a no-op.
        assertEquals(0, run(pass, batchLimit = 2, now = START_EPOCH + interval - 1L))
        // After the interval elapses, the pass runs again and fills the rest.
        assertEquals(2, run(pass, batchLimit = 2, now = START_EPOCH + interval + 1L))
        assertEquals(4, prober.probedUrls.size)
    }

    @Test
    fun `no unknown chapters means nothing is probed`() {
        val prober = FakeProber(emptyMap())
        seed(listOf(book("known")), listOf(chapter("known", 0, 600L), chapter("known", 1, 720L)))

        assertEquals(0, run(pass(prober)))
        assertTrue(prober.probedUrls.isEmpty())
    }

    companion object {
        /** Realistic epoch so the very first pass is never throttled. */
        private const val START_EPOCH = 1_700_000_000_000L
    }
}
