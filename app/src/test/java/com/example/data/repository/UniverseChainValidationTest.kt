package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.db.SeriesEntity
import com.example.data.db.UniverseEntity
import com.example.data.db.WorkEntity
import com.example.data.universe.ResolutionProvenance
import com.example.data.universe.SeriesUniverses
import com.example.data.universe.SeriesUniverseProvider
import com.example.data.universe.SharedUniverseStore
import com.example.data.universe.UniverseList
import com.example.data.universe.UniverseResolution
import com.example.data.universe.UniverseSeries
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
 * Spec-26 T8 (#182) — the import-triggered chain validation over the real
 * Room SQL: a new book whose series belongs to a CACHED universe re-resolves
 * that universe through the provider — the chain grows (precedes/follows and
 * new series appear) and the fresh resolution writes back to the shared base
 * with provenance. An unknown series, or a failing resolve, is a silent no-op
 * — the cached chain stays untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UniverseChainValidationTest {

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

    /** Nothing curated — the "known universe" signal is purely the cache. */
    private val universes = emptyList<UniverseList>()

    /** The CHANGED chain the provider now returns: a new series appeared. */
    private val changedChain = UniverseResolution(
        universe = UniverseList(
            id = "wd:Q900",
            name = "Невідомий всесвіт",
            series = listOf(
                UniverseSeries(title = "Серія А"),
                UniverseSeries(title = "Серія Б"),
                UniverseSeries(title = "Серія В")
            )
        ),
        matchedSeries = UniverseSeries(title = "Серія В"),
        position = 3
    )

    private suspend fun seedCachedUniverse() {
        dao.upsertUniverse(UniverseEntity(id = "wd:Q900", name = "Невідомий всесвіт"))
        dao.upsertSeries(
            SeriesEntity(id = "wd:Q900:1", title = "Серія А", universeId = "wd:Q900", positionInUniverse = 1)
        )
        dao.upsertSeries(
            SeriesEntity(id = "wd:Q900:2", title = "Серія Б", universeId = "wd:Q900", positionInUniverse = 2)
        )
    }

    private suspend fun seedWork(seriesTitle: String) {
        dao.upsertWork(
            WorkEntity(
                id = "w1",
                mergeKey = "$seriesTitle|автор",
                title = "Книга",
                author = "Автор",
                seriesTitle = seriesTitle,
                seriesUrl = null,
                seriesIndex = 1
            )
        )
    }

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

    private fun resolverWith(
        provider: SeriesUniverseProvider,
        store: SharedUniverseStore? = null
    ): SeriesUniverses = SeriesUniverses(
        dao,
        universes,
        wikidata = provider,
        sharedStore = store,
        tierTtlMillis = { _, _, _ -> 10_000L },
        now = { 1_000_000L }
    )

    @Test
    fun `a new book of a cached universe validates the chain and writes back`() = runBlocking {
        seedCachedUniverse()
        seedWork("Серія Б") // matches a cached series by title
        val store = RecordingStore()
        var calls = 0
        val resolver = resolverWith(object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                calls++
                return changedChain
            }
        }, store)

        resolver.validateChainFor("w1")

        // The provider was consulted once — the event trigger is immediate.
        assertEquals(1, calls)
        // The chain grew: the new series row appears, the order is preserved.
        assertEquals(
            listOf("Серія А", "Серія Б", "Серія В"),
            dao.getSeriesInUniverse("wd:Q900").map { it.title }
        )
        assertEquals(
            listOf(1, 2, 3),
            dao.getSeriesInUniverse("wd:Q900").map { it.positionInUniverse }
        )
        // The new book's membership lands on the chain tail.
        assertEquals(listOf("wd:Q900:3"), dao.getSeriesMembersForWork("w1").map { it.seriesId })
        // AC2: the fresh chain was written back to the shared base.
        assertEquals(1, store.writes.size)
        val (workId, written, provenance) = store.writes[0]
        assertEquals("w1", workId)
        assertEquals(listOf("Серія А", "Серія Б", "Серія В"), written.universe.series.map { it.title })
        assertEquals(ResolutionProvenance.SOURCE_WIKIDATA, provenance.source)
        assertTrue(provenance.authorVerified)
    }

    @Test
    fun `an unknown series is a silent no-op`() = runBlocking {
        seedCachedUniverse()
        seedWork("Невідомий цикл") // not in any cached universe
        val store = RecordingStore()
        var calls = 0
        val resolver = resolverWith(object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                calls++
                return changedChain
            }
        }, store)

        resolver.validateChainFor("w1")

        // Nothing consulted, nothing written, the cache is untouched.
        assertEquals(0, calls)
        assertTrue(store.writes.isEmpty())
        assertEquals(listOf("Серія А", "Серія Б"), dao.getSeriesInUniverse("wd:Q900").map { it.title })
        assertTrue(dao.getSeriesMembersForWork("w1").isEmpty())
    }

    @Test
    fun `a failing resolve leaves the cached chain untouched`() = runBlocking {
        seedCachedUniverse()
        seedWork("Серія Б")
        val store = RecordingStore()
        val resolver = resolverWith(object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? = null
        }, store)

        resolver.validateChainFor("w1")

        assertEquals(listOf("Серія А", "Серія Б"), dao.getSeriesInUniverse("wd:Q900").map { it.title })
        assertTrue(store.writes.isEmpty())
        assertTrue(dao.getSeriesMembersForWork("w1").isEmpty())
    }

    @Test
    fun `a cached series row without a universe is a silent no-op`() = runBlocking {
        // A series row exists but is not part of any universe — no validation.
        dao.upsertSeries(SeriesEntity(id = "s9", title = "Серія X", universeId = null, positionInUniverse = null))
        seedWork("Серія X")
        var calls = 0
        val resolver = resolverWith(object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                calls++
                return changedChain
            }
        })

        resolver.validateChainFor("w1")

        assertEquals(0, calls)
        assertTrue(dao.getSeriesMembersForWork("w1").isEmpty())
    }
}
