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
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.metadata.DurationProvenance
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

    /**
     * #350 — records the peak number of probes in flight, so the bounded
     * parallelism of the sweep is observable from the outside.
     */
    private class ConcurrencyRecordingProber(
        private val result: ProbeResult
    ) : StreamProber {
        val probedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val active = java.util.concurrent.atomic.AtomicInteger(0)
        val maxActive = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun probe(url: String): ProbeResult? {
            val now = active.incrementAndGet()
            maxActive.accumulateAndGet(now) { previous, current -> maxOf(previous, current) }
            kotlinx.coroutines.delay(50)
            active.decrementAndGet()
            probedUrls += url
            return result
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
        books.forEach { book ->
            dao.upsertLibraryEntry(
                id = book.id,
                workId = book.id,
                isFavorite = false,
                createdAt = book.createdAt,
                downloadProgress = 0f
            )
        }
        dao.insertChapters(chapters)
    }

    /** One pass instance — the CAS throttle lives on it, so reuse matters. */
    private fun pass(
        prober: StreamProber,
        tracks: Map<String, Map<Int, SourceTrackEntity>> = emptyMap(),
        store: FakeSharedBookMetaStore? = null
    ): ChapterDurationProbe {
        val provider: suspend (String) -> List<PlayableChapter> = { bookId -> pairs(bookId, tracks[bookId].orEmpty()) }
        return ChapterDurationProbe(dao, provider, prober, sharedStore = store)
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

    // ---------------------------------------------------------------------
    // #349 — the targeted post-import probe (fire-and-forget, per book)
    // ---------------------------------------------------------------------

    @Test
    fun `targeted probe fills one book regardless of the hourly throttle`() {
        val tracks = mapOf("a" to mapOf(0 to track("a", 0, "https://provider.example/a/1.mp3")))
        val prober = FakeProber(
            mapOf("https://provider.example/a/1.mp3" to ProbeResult(contentLength = 4_410_000, frame = cbr128))
        )
        val pass = pass(prober, tracks)

        // The background pass consumes its hourly window on an empty library…
        assertEquals(0, run(pass))
        // …the book arrives afterwards — the targeted probe still runs NOW.
        seed(listOf(book("a")), listOf(chapter("a", 0, 0L)))

        assertEquals(1, runBlocking { pass.probeBookNow("a") })
        assertEquals(275L, durationOf("a-ch0"))
    }

    @Test
    fun `targeted probe is single-flight per book`() = runBlocking {
        class GatedProber : StreamProber {
            val started = java.util.concurrent.atomic.AtomicInteger(0)
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            override suspend fun probe(url: String): ProbeResult? {
                started.incrementAndGet()
                release.await()
                return ProbeResult(contentLength = 4_410_000, frame = cbr128)
            }
        }
        seed(listOf(book("a")), listOf(chapter("a", 0, 0L)))
        val prober = GatedProber()
        val passInstance = pass(prober, mapOf("a" to mapOf(0 to track("a", 0, "https://provider.example/a/1.mp3"))))

        var firstResult = -1
        val first = launch(kotlinx.coroutines.Dispatchers.IO) { firstResult = passInstance.probeBookNow("a") }
        while (prober.started.get() < 1) kotlinx.coroutines.delay(10)

        // The concurrent second call loses the single-flight race and probes nothing.
        val secondDeferred = async(kotlinx.coroutines.Dispatchers.IO) { passInstance.probeBookNow("a") }
        val secondResult = secondDeferred.await()

        prober.release.complete(Unit)
        first.join()
        assertEquals(0, secondResult)
        assertEquals(1, prober.started.get())
        assertEquals(1, firstResult)
    }

    @Test
    fun `targeted probe skips local books and unknown ids`() = runBlocking {
        val prober = FakeProber(emptyMap())
        seed(listOf(book("local", url = "")), listOf(chapter("local", 0, 0L)))
        val pass = pass(prober)

        assertEquals(0, pass.probeBookNow("local"))
        assertEquals(0, pass.probeBookNow("missing-book"))
        assertTrue(prober.probedUrls.isEmpty())
        assertEquals(0L, durationOf("local-ch0"))
    }

    // ---------------------------------------------------------------------
    // #350 — the tuned background sweep
    // ---------------------------------------------------------------------

    @Test
    fun `the pass probes the newest books first`() {
        val tracks = mapOf(
            "old" to mapOf(0 to track("old", 0, "https://provider.example/old/1.mp3")),
            "new" to mapOf(0 to track("new", 0, "https://provider.example/new/1.mp3"))
        )
        val prober = FakeProber(emptyMap()) // only the recorded urls matter here
        seed(listOf(book("old"), book("new")), listOf(chapter("old", 0, 0L), chapter("new", 0, 0L)))
        runBlocking {
            dao.upsertLibraryEntry("old", "old", false, START_EPOCH, 0f)
            dao.upsertLibraryEntry("new", "new", false, START_EPOCH + 60_000L, 0f)
        }

        run(pass(prober, tracks), batchLimit = 1)
        assertEquals(1, prober.probedUrls.size)
        assertEquals(listOf("https://provider.example/new/1.mp3"), prober.probedUrls)
    }

    @Test
    fun `chapters of one book probe with bounded parallelism`() = runBlocking {
        val count = 8
        seed(listOf(book("p")), (0 until count).map { chapter("p", it, 0L) })
        val tracks = (0 until count).associateWith { index ->
            track("p", index, "https://provider.example/p/$index.mp3")
        }
        val prober = ConcurrencyRecordingProber(ProbeResult(contentLength = 4_410_000, frame = cbr128))

        val probed = run(pass(prober, mapOf("p" to tracks)))

        assertEquals(count, probed)
        assertEquals(count, prober.probedUrls.size)
        assertTrue(
            "max parallel ${prober.maxActive.get()}",
            prober.maxActive.get() <= ChapterDurationProbe.DEFAULT_PROBE_CONCURRENCY
        )
        assertTrue("no overlap observed", prober.maxActive.get() > 1)
    }

    companion object {
        /** Realistic epoch so the very first pass is never throttled. */
        private const val START_EPOCH = 1_700_000_000_000L
    }

    // --- T4 (#219): a probed book total writes back to the shared store ----

    @Test
    fun `a book total derived from probed chapters writes back`() = runBlocking {
        val store = FakeSharedBookMetaStore()
        seed(listOf(book("b1")), listOf(chapter("b1", 0, 0L), chapter("b1", 1, 0L)))
        val tr0 = track("b1", 0, "https://provider.example/b1/1.mp3")
        val tr1 = track("b1", 1, "https://provider.example/b1/2.mp3")
        dao.insertTracks(listOf(tr0, tr1))
        // 96_000*8/(128*1000) = 6 s; 48_000*8/(128*1000) = 3 s → total 9 s.
        val prober = FakeProber(
            resultFor = mapOf(
                "https://provider.example/b1/1.mp3" to ProbeResult(contentLength = 96_000L, frame = cbr128),
                "https://provider.example/b1/2.mp3" to ProbeResult(contentLength = 48_000L, frame = cbr128)
            )
        )

        val probed = run(pass(prober, tracks = mapOf("b1" to mapOf(0 to tr0, 1 to tr1)), store = store))

        assertTrue(probed > 0)
        assertEquals(1, store.durationPuts.size)
        val (editionId, total, provenance) = store.durationPuts.single()
        assertEquals(EditionId.forBook("", "b1", ""), editionId)
        assertEquals("unknown", provenance.source)
        assertEquals(DurationProvenance.METHOD_TECHNICAL_PROBE, provenance.method)
        // 96_000*8/128/1000 + 48_000*8/128/1000 = 6 + 3 = 9 seconds.
        assertEquals(9L, total)
    }
}
