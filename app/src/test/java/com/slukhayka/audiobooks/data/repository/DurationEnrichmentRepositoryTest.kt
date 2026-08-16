package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.duration.DurationBuckets
import com.slukhayka.audiobooks.data.duration.DurationEnrichment
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import java.io.IOException
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
 * spec-18 T2 (#113) — the background duration enrichment pass, through the
 * existing source adapter seam (prior art: HydrationRepositoryTest). A fake
 * adapter reports real durations; the pass writes them into book rows for
 * unknown-duration books only, respects its batch limit, survives failing
 * fetches, and throttles itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DurationEnrichmentRepositoryTest {

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

    /**
     * Records which book URLs were fetched, returns the configured duration
     * (null = page without a duration), and throws [IOException] for urls not
     * configured — the failing-fetch case.
     */
    private class FakeBookAdapter(
        private val durationFor: Map<String, Long?>
    ) : SourceAdapter {
        val fetchedUrls = mutableListOf<String>()
        override val sourceId: String get() = "4read"
        override val sessionBound: Boolean get() = false

        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = emptyList()

        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            fetchedUrls += url
            val duration = durationFor[url] ?: throw IOException("network down")
            return SourceBookDetail(
                title = "",
                author = "",
                url = url,
                chapters = emptyList(),
                totalDurationSeconds = duration
            )
        }
    }

    private fun book(id: String, durationSeconds: Long, url: String = "https://4read.org/$id.html") =
        AudiobookEntity(
            id = id,
            title = "Книга $id",
            author = "Автор",
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            sourceUrl = url,
            genre = "",
            totalDurationSeconds = durationSeconds
        )

    private fun repo(adapter: FakeBookAdapter) =
        DurationEnrichment(dao, adapter::fetchBookPage)

    private fun runEnrich(repo: DurationEnrichment, batchLimit: Int = 5, now: Long = 1_700_000_000_000L): Int =
        runBlocking { repo.enrichUnknownDurations(batchLimit = batchLimit, now = { now }) }

    private fun durationOf(id: String): Long? = runBlocking { dao.getAudiobookById(id) }?.totalDurationSeconds

    // ---------------------------------------------------------------------
    // Writes real durations, only for unknown-duration books
    // ---------------------------------------------------------------------

    @Test
    fun `unknown-duration books receive their real durations from the seam`() {
        runBlocking {
            dao.insertAudiobooks(
                listOf(
                    book("a", 0L),
                    book("b", 0L),
                    book("known", 1234L)
                )
            )
        }
        val adapter = FakeBookAdapter(
            mapOf(
                "https://4read.org/a.html" to 7200L,
                "https://4read.org/b.html" to 43_200L
            )
        )
        val repo = repo(adapter)

        assertEquals(2, runEnrich(repo))
        assertEquals(7200L, durationOf("a"))
        assertEquals(43_200L, durationOf("b"))
        // The known-duration book was never fetched — the pass only looks at
        // books whose duration is missing.
        assertEquals(setOf("a", "b"), adapter.fetchedUrls.map { it.substringAfterLast("/").removeSuffix(".html") }.toSet())
    }

    @Test
    fun `the fabricated legacy 4-hour placeholder counts as unknown and is enriched`() {
        runBlocking { dao.insertAudiobooks(listOf(book("legacy", DurationBuckets.FABRICATED_LEGACY_SECONDS))) }
        val adapter = FakeBookAdapter(mapOf("https://4read.org/legacy.html" to 9000L))

        assertEquals(1, runEnrich(repo(adapter)))
        assertEquals(9000L, durationOf("legacy"))
    }

    // ---------------------------------------------------------------------
    // Failure isolation and honest writes
    // ---------------------------------------------------------------------

    @Test
    fun `a failing fetch leaves the row untouched and does not abort the batch`() {
        runBlocking {
            dao.insertAudiobooks(listOf(book("fail", 0L), book("ok", 0L)))
        }
        val adapter = FakeBookAdapter(mapOf("https://4read.org/ok.html" to 5400L))
        val repo = repo(adapter)

        assertEquals(1, runEnrich(repo))
        assertEquals(0L, durationOf("fail"))
        assertEquals(5400L, durationOf("ok"))
    }

    @Test
    fun `a page without a duration never writes zero over the row`() {
        runBlocking { dao.insertAudiobooks(listOf(book("noduration", 0L))) }
        val adapter = FakeBookAdapter(mapOf("https://4read.org/noduration.html" to null))
        val repo = repo(adapter)

        assertEquals(0, runEnrich(repo))
        assertEquals(0L, durationOf("noduration"))
    }

    @Test
    fun `local imports with a blank source url are skipped, never fetched`() {
        runBlocking { dao.insertAudiobooks(listOf(book("local", 0L, url = ""))) }
        val adapter = FakeBookAdapter(emptyMap())
        val repo = repo(adapter)

        assertEquals(0, runEnrich(repo))
        assertTrue(adapter.fetchedUrls.isEmpty())
        assertEquals(0L, durationOf("local"))
    }

    // ---------------------------------------------------------------------
    // Bounded batch and throttle
    // ---------------------------------------------------------------------

    @Test
    fun `the pass respects its batch limit`() {
        runBlocking {
            dao.insertAudiobooks((1..5).map { book("b$it", 0L) })
        }
        val adapter = FakeBookAdapter(
            (1..5).associate { "https://4read.org/b$it.html" to 3600L }
        )
        val repo = repo(adapter)

        assertEquals(2, runEnrich(repo, batchLimit = 2))
        assertEquals(2, adapter.fetchedUrls.size)
        assertEquals(
            3600L,
            durationOf(adapter.fetchedUrls.first().substringAfterLast("/").removeSuffix(".html"))
        )
    }

    @Test
    fun `the pass throttles itself between runs`() {
        runBlocking {
            dao.insertAudiobooks((1..4).map { book("c$it", 0L) })
        }
        val adapter = FakeBookAdapter((1..4).associate { "https://4read.org/c$it.html" to 3600L })
        val repo = repo(adapter)
        val interval = DurationEnrichment.MIN_ENRICHMENT_INTERVAL_MS

        // First run: enriches the first two (batch limit 2).
        assertEquals(2, runEnrich(repo, batchLimit = 2, now = START_EPOCH))
        // Second run within the interval: throttled to a no-op.
        assertEquals(0, runEnrich(repo, batchLimit = 2, now = START_EPOCH + interval - 1L))
        // After the interval elapses, the pass runs again and fills the rest.
        assertEquals(2, runEnrich(repo, batchLimit = 2, now = START_EPOCH + interval + 1L))
        assertEquals(4, adapter.fetchedUrls.size)
    }

    companion object {
        /** Realistic epoch so the very first pass is never throttled. */
        private const val START_EPOCH = 1_700_000_000_000L
    }

    @Test
    fun `no unknown-duration books means nothing is fetched`() {
        runBlocking { dao.insertAudiobooks(listOf(book("known", 3600L), book("also", 86_400L))) }
        val adapter = FakeBookAdapter(emptyMap())
        val repo = repo(adapter)

        assertEquals(0, runEnrich(repo))
        assertTrue(adapter.fetchedUrls.isEmpty())
        assertEquals("existing durations are never touched", 3600L, durationOf("known"))
    }
}