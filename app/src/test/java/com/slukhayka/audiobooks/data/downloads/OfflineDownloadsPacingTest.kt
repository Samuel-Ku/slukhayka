package com.slukhayka.audiobooks.data.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.privacy.PacingParams
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.testing.FakeFetcher
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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

/**
 * Spec-38 T5 (#257) — the human rhythm of downloads, pinned at the module
 * seam: every fresh chapter fetch rides the injected [PacingPolicy] — first
 * a random pause inside the policy range (zero pauses impossible), then a
 * burst-budget slot for the track's domain; a refusal costs another policy
 * pause, and the loop owns no thresholds of its own.
 *
 * The chapter workers run on Dispatchers.IO (real time), so the virtual clock
 * lives HERE: the injected pause advances a local counter instead of sleeping,
 * and [OfflineDownloads]'s injected `nowMillis` reads it back — the same
 * deterministic timeline, no real waiting. The cancellation test swaps in an
 * endless pause so the cancel provably lands DURING the opening pause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineDownloadsPacingTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private val dao get() = db.audiobookDao()

    /** Virtual clock: pauses move it forward without sleeping; fetches read it. */
    private class VirtualClock {
        private val now = AtomicLong(0)
        fun millis(): Long = now.get()
        suspend fun advanceBy(millis: Long) {
            now.addAndGet(millis)
        }
    }

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
        private val numChapters: Int,
        private val streamUrl: (Int) -> String
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

    /** One distinct-content response per chapter URL so hash dedup (spec-37 T2)
     *  cannot collapse them — every chapter must take the fresh-network path
     *  where the pacing lives. */
    private fun fetcherFor(urls: List<String>): FakeFetcher {
        val responses = urls.associateWith { url ->
            val idx = urls.indexOf(url)
            val audio = ByteArray(2048) { (it + idx).toByte() }
            audio to audio.size.toLong()
        }
        return FakeFetcher(sizedStreamResponses = responses)
    }

    private suspend fun harness(
        numChapters: Int,
        pacing: PacingPolicy,
        nowMillis: () -> Long,
        pauseFor: suspend (Long) -> Unit,
        fetcher: FakeFetcher
    ): Pair<LibraryImport, OfflineDownloads> {
        val book = SourceBook(title = "Пасажир", author = "Жан-Крістоф Гранже", url = "https://sluhay.com/svitova-literatura/6177-pasazhir.html", sourceId = "sluhay")
        val adapter = FakeAdapter("sluhay", book, numChapters, { i -> "https://cdn.example.com/track-$i.mp3" })
        val imports = LibraryImport(dao, context, listOf(adapter))
        val catalog = SourceCatalog(dao, listOf(adapter), imports)
        val downloads = OfflineDownloads(dao, context, catalog, fetcher, pacing, nowMillis, pauseFor)
        return imports to downloads
    }

    private suspend fun importBook(imports: LibraryImport): String {
        val imported = imports.importFromSourceUrl("sluhay", "https://sluhay.com/svitova-literatura/6177-pasazhir.html")
        return imported!!.id
    }

    @Test
    fun `every fresh fetch waits at least the policy minimum pause - zero pause impossible`() = runTest {
        val params = PacingParams(
            minPauseMillis = 1_000,
            maxPauseMillis = 3_000,
            burstLimit = 6,
            burstWindowMillis = 60_000
        )
        val attempts = mutableListOf<Long>()
        val clock = VirtualClock()
        val fetcher = fetcherFor((0 until 4).map { "https://cdn.example.com/track-$it.mp3" })
        val (imports, downloads) = harness(
            numChapters = 4,
            pacing = PacingPolicy(params, Random(42)),
            nowMillis = { attempts.add(clock.millis()); clock.millis() },
            pauseFor = { millis -> clock.advanceBy(millis) },
            fetcher = fetcher
        )
        val bookId = importBook(imports)

        val result = downloads.downloadAudiobookOffline(bookId)

        assertEquals(4, result.downloadedChapters)
        assertEquals("the policy is consulted exactly once per fresh fetch", 4, attempts.size)
        assertTrue(
            "every fetch sits behind a pause of at least the policy minimum",
            attempts.all { it >= params.minPauseMillis }
        )
    }

    @Test
    fun `a burst refusal costs another policy pause and the download still completes`() = runTest {
        // Two slots per window; the earliest hits slide out after one more
        // pause or two, so the refused third/fourth chapters must retry after
        // further policy pauses — at least six consultations, and the virtual
        // timeline necessarily stretches past two minimum pauses. A refusal
        // waits; it never fails the chapter and never spins forever.
        val params = PacingParams(
            minPauseMillis = 400,
            maxPauseMillis = 800,
            burstLimit = 2,
            burstWindowMillis = 1_600
        )
        val attempts = mutableListOf<Long>()
        val clock = VirtualClock()
        val fetcher = fetcherFor((0 until 4).map { "https://cdn.example.com/track-$it.mp3" })
        val (imports, downloads) = harness(
            numChapters = 4,
            pacing = PacingPolicy(params, Random(7)),
            nowMillis = { attempts.add(clock.millis()); clock.millis() },
            pauseFor = { millis -> clock.advanceBy(millis) },
            fetcher = fetcher
        )
        val bookId = importBook(imports)

        val result = downloads.downloadAudiobookOffline(bookId)

        assertEquals("refused chapters wait, they do not fail", 4, result.downloadedChapters)
        assertTrue(
            "four fetches against a two-slot window mean refusals happened",
            attempts.size >= 6
        )
        assertTrue(
            "a refusal costs another pause, so time passes twice",
            attempts.max() >= 2 * params.minPauseMillis
        )
    }

    @Test
    fun `cancelling during the opening pause aborts before any fetch`() = runTest {
        val clock = VirtualClock()
        val attempts = mutableListOf<Long>()
        val fetcher = fetcherFor(listOf("https://cdn.example.com/track-0.mp3", "https://cdn.example.com/track-1.mp3"))
        val (imports, downloads) = harness(
            numChapters = 2,
            pacing = PacingPolicy(PacingParams(minPauseMillis = 600_000, maxPauseMillis = 900_000), Random(5)),
            nowMillis = { attempts.add(clock.millis()); clock.millis() },
            pauseFor = { _ -> awaitCancellation() },
            fetcher = fetcher
        )
        val bookId = importBook(imports)

        val job = launch { downloads.downloadAudiobookOffline(bookId) }
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue("nothing was fetched after the cancel", fetcher.recordedHeaders.isEmpty())
        assertTrue("the policy was never consulted", attempts.isEmpty())
        dao.getTracksForBookSync(bookId).forEach { track ->
            assertFalse(track.isDownloaded)
            assertNull(track.localFilePath)
        }
    }
}
