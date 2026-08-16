package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.SeriesEntity
import com.slukhayka.audiobooks.data.db.SeriesMemberEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.universe.ResolutionProvenance
import com.slukhayka.audiobooks.data.universe.SeriesUniverses
import com.slukhayka.audiobooks.data.universe.SeriesUniverseProvider
import com.slukhayka.audiobooks.data.universe.SharedUniverseStore
import com.slukhayka.audiobooks.data.universe.UniverseList
import com.slukhayka.audiobooks.data.universe.UniverseRefreshPass
import com.slukhayka.audiobooks.data.universe.UniverseResolution
import com.slukhayka.audiobooks.data.universe.UniverseSeries
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-26 T7 (#181) — the background refresh pass over the real Room SQL:
 * expired-tier memberships re-resolve by priority (most overdue first), a
 * fresh membership is skipped, a run is bounded, and the re-resolution
 * writes back to the shared base (spec-26 T6 provenance write — AC5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UniverseRefreshPassTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    private val t0 = 1_700_000_000_000L

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

    /** Nothing curated — every seeded series needs the provider. */
    private val universes = emptyList<UniverseList>()

    private val resolution = UniverseResolution(
        universe = UniverseList(
            id = "wd:Q900",
            name = "Невідомий всесвіт",
            series = listOf(UniverseSeries(title = "Серія А"), UniverseSeries(title = "Серія Б"))
        ),
        matchedSeries = UniverseSeries(title = "Серія Б"),
        position = 2
    )

    private suspend fun seedWork(workId: String, seriesTitle: String) {
        dao.upsertWork(
            WorkEntity(
                id = workId,
                mergeKey = "$seriesTitle|автор",
                title = seriesTitle,
                author = "Автор",
                seriesTitle = seriesTitle,
                seriesUrl = null,
                seriesIndex = 1
            )
        )
    }

    private suspend fun seedSeries(seriesId: String, universeId: String, position: Int) {
        dao.upsertSeries(
            SeriesEntity(id = seriesId, title = "Серія $seriesId", universeId = universeId, positionInUniverse = position)
        )
    }

    private fun resolverWith(
        provider: SeriesUniverseProvider,
        sharedStore: SharedUniverseStore? = null
    ): SeriesUniverses = SeriesUniverses(
        dao,
        universes,
        wikidata = provider,
        sharedStore = sharedStore,
        tierTtlMillis = { _, _, _ -> 1_000L },
        now = { t0 }
    )

    private fun passWith(
        resolver: SeriesUniverses,
        maxPerRun: Int = 10,
        paceMillis: Long = 0L
    ): UniverseRefreshPass = UniverseRefreshPass(
        dao,
        resolver,
        tierTtlMillis = { _, _, _ -> 1_000L },
        now = { t0 },
        maxPerRun = maxPerRun,
        paceMillis = paceMillis
    )

    private class RecordingStore : SharedUniverseStore {
        val writes = mutableListOf<Triple<String, UniverseResolution, ResolutionProvenance>>()
        private val docs = mutableMapOf<String, UniverseResolution>()
        override suspend fun getResolution(workId: String): UniverseResolution? = docs[workId]
        override suspend fun putResolution(
            workId: String,
            resolution: UniverseResolution,
            provenance: ResolutionProvenance
        ) {
            writes += Triple(workId, resolution, provenance)
            docs[workId] = resolution
        }
    }

    @Test
    fun `stale memberships re-resolve by priority - most overdue first`() = runBlocking {
        seedWork("w1", "Невідомий 1")
        seedWork("w2", "Невідомий 2")
        seedWork("w3", "Невідомий 3")
        seedSeries("s1", "u1", 1)
        seedSeries("s2", "u2", 1)
        seedSeries("s3", "u3", 1)
        dao.upsertSeriesMember(SeriesMemberEntity("w1", "s1", 1, resolvedAt = t0 - 100_000))
        dao.upsertSeriesMember(SeriesMemberEntity("w2", "s2", 1, resolvedAt = t0 - 50_000))
        dao.upsertSeriesMember(SeriesMemberEntity("w3", "s3", 1, resolvedAt = t0 - 25_000))
        val order = mutableListOf<String>()
        val pass = passWith(
            resolverWith(object : SeriesUniverseProvider {
                override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                    order += bookTitle
                    return resolution
                }
            })
        )

        val count = pass.runOnce()

        assertEquals(3, count)
        // The most-overdue work re-resolves first.
        assertEquals(listOf("Невідомий 1", "Невідомий 2", "Невідомий 3"), order)
    }

    @Test
    fun `a fresh membership is skipped`() = runBlocking {
        seedWork("w1", "Невідомий 1")
        seedSeries("s1", "u1", 1)
        dao.upsertSeriesMember(SeriesMemberEntity("w1", "s1", 1, resolvedAt = t0 - 500))
        var calls = 0
        val pass = passWith(
            resolverWith(object : SeriesUniverseProvider {
                override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                    calls++
                    return resolution
                }
            })
        )

        assertEquals(0, pass.runOnce())
        assertEquals(0, calls)
    }

    @Test
    fun `a run is capped at maxPerRun and takes the most overdue`() = runBlocking {
        seedWork("w1", "Невідомий 1")
        seedWork("w2", "Невідомий 2")
        seedWork("w3", "Невідомий 3")
        seedSeries("s1", "u1", 1)
        seedSeries("s2", "u2", 1)
        seedSeries("s3", "u3", 1)
        dao.upsertSeriesMember(SeriesMemberEntity("w1", "s1", 1, resolvedAt = t0 - 100_000))
        dao.upsertSeriesMember(SeriesMemberEntity("w2", "s2", 1, resolvedAt = t0 - 50_000))
        dao.upsertSeriesMember(SeriesMemberEntity("w3", "s3", 1, resolvedAt = t0 - 25_000))
        val order = mutableListOf<String>()
        val pass = passWith(
            resolverWith(object : SeriesUniverseProvider {
                override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                    order += bookTitle
                    return resolution
                }
            }),
            maxPerRun = 2
        )

        assertEquals(2, pass.runOnce())
        assertEquals(listOf("Невідомий 1", "Невідомий 2"), order)
    }

    @Test
    fun `the pass writes the fresh resolution back to the shared store`() = runBlocking {
        // AC5: the re-resolution rides the book-open path, which persists to
        // Room and writes back to the shared base with provenance.
        seedWork("w1", "Невідомий цикл")
        seedSeries("s1", "u1", 1)
        dao.upsertSeriesMember(SeriesMemberEntity("w1", "s1", 1, resolvedAt = t0 - 100_000))
        val store = RecordingStore()
        val pass = passWith(
            resolverWith(
                object : SeriesUniverseProvider {
                    override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? =
                        resolution
                },
                sharedStore = store
            )
        )

        pass.runOnce()

        assertEquals(1, store.writes.size)
        val (workId, written, provenance) = store.writes[0]
        assertEquals("w1", workId)
        assertEquals(resolution, written)
        assertEquals(ResolutionProvenance.SOURCE_WIKIDATA, provenance.source)
        // The Room cache was refreshed too.
        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
    }
}
