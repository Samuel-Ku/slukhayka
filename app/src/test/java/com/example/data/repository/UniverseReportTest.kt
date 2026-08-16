package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.db.AudiobookEntity
import com.example.data.db.CorrectionEntity
import com.example.data.db.CorrectionKind
import com.example.data.db.CorrectionOrigin
import com.example.data.db.SeriesEntity
import com.example.data.db.SeriesMemberEntity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-26 T9 (#183) — the «wrong universe» feedback channel over the real
 * Room SQL: a complaint pins the work as reported (WRONG_UNIVERSE
 * correction) and hides its resolution; the immediate re-resolution verdict
 * either REPLACEs the cached chain AND the shared-base document (mismatch —
 * for everyone), or clears the complaint with nothing changed (match — the
 * cached one was right). A failing resolve keeps the report, so the
 * resolution stays hidden until the next retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UniverseReportTest {

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

    private val universes = emptyList<UniverseList>()

    /** The cached universe the user complains about. */
    private val cachedUniverse = UniverseResolution(
        universe = UniverseList(
            id = "wd:Q900",
            name = "Невідомий всесвіт",
            series = listOf(UniverseSeries(title = "Серія А"), UniverseSeries(title = "Серія Б"))
        ),
        matchedSeries = UniverseSeries(title = "Серія Б"),
        position = 2
    )

    /** A DIFFERENT universe — the verdict for a true complaint. */
    private val otherUniverse = UniverseResolution(
        universe = UniverseList(
            id = "wd:Q901",
            name = "Інший всесвіт",
            series = listOf(UniverseSeries(title = "Серія X"), UniverseSeries(title = "Серія Y"))
        ),
        matchedSeries = UniverseSeries(title = "Серія Y"),
        position = 2
    )

    private suspend fun seedCachedResolution() {
        dao.upsertUniverse(UniverseEntity(id = "wd:Q900", name = "Невідомий всесвіт"))
        dao.upsertSeries(
            SeriesEntity(id = "wd:Q900:1", title = "Серія А", universeId = "wd:Q900", positionInUniverse = 1)
        )
        dao.upsertSeries(
            SeriesEntity(id = "wd:Q900:2", title = "Серія Б", universeId = "wd:Q900", positionInUniverse = 2)
        )
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = "b1",
                    title = "Книга",
                    author = "Автор",
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    sourceUrl = "https://4read.org/kniga.html",
                    genre = ""
                )
            )
        )
        dao.upsertLibraryEntry(id = "b1", workId = "w1", isFavorite = false, createdAt = 0L, downloadProgress = 0f)
        dao.upsertWork(
            WorkEntity(
                id = "w1",
                mergeKey = "книга|автор",
                title = "Книга",
                author = "Автор",
                seriesTitle = "Серія Б",
                seriesUrl = null,
                seriesIndex = 1
            )
        )
        dao.upsertSeriesMember(
            SeriesMemberEntity(workId = "w1", seriesId = "wd:Q900:2", position = 1, resolvedAt = 1_000_000L)
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
    fun `a reported work's resolution is hidden until the report clears`() = runBlocking {
        seedCachedResolution()
        val resolver = resolverWith(object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? = null
        })

        // Resolved before the complaint.
        assertEquals("Невідомий всесвіт", resolver.contextOfBook("b1")!!.universeName)

        // The complaint pins the work → the resolution hides (AC2).
        dao.upsertCorrection(
            CorrectionEntity(
                mergeKey = "w1",
                kind = CorrectionKind.WRONG_UNIVERSE,
                value = "wd:Q900",
                origin = CorrectionOrigin.USER_MADE,
                updatedAt = 1_000_000L
            )
        )
        assertNull(resolver.contextOfBook("b1"))

        // Clearing the complaint restores the resolution.
        dao.deleteCorrection("w1", CorrectionKind.WRONG_UNIVERSE)
        assertEquals("Невідомий всесвіт", resolver.contextOfBook("b1")!!.universeName)
    }

    @Test
    fun `a mismatch verdict replaces the chain and writes back for everyone`() = runBlocking {
        seedCachedResolution()
        val store = RecordingStore()
        var calls = 0
        val resolver = resolverWith(object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                calls++
                return otherUniverse
            }
        }, store)

        resolver.reportWrongUniverseForBook("b1")

        // The complaint was verified — the fresh chain replaced the cached one.
        assertEquals(1, calls)
        assertEquals("Інший всесвіт", dao.getUniverseById("wd:Q901")!!.name)
        assertEquals(listOf("Серія X", "Серія Y"), dao.getSeriesInUniverse("wd:Q901").map { it.title })
        assertEquals(listOf("wd:Q901:2"), dao.getSeriesMembersForWork("w1").map { it.seriesId })
        // AC: the correction reaches EVERYONE — the fresh resolution was
        // written to the shared base (T6 write-back with provenance).
        assertEquals(1, store.writes.size)
        val (workId, written, provenance) = store.writes[0]
        assertEquals("w1", workId)
        assertEquals("wd:Q901", written.universe.id)
        assertEquals(ResolutionProvenance.SOURCE_WIKIDATA, provenance.source)
        // The complaint is cleared and the corrected universe shows.
        assertTrue(dao.getCorrectionsForMergeKey("w1").none { it.kind == CorrectionKind.WRONG_UNIVERSE })
        assertEquals("Інший всесвіт", resolver.contextOfBook("b1")!!.universeName)
    }

    @Test
    fun `a matching verdict ignores the complaint - nothing changes`() = runBlocking {
        seedCachedResolution()
        val store = RecordingStore()
        var calls = 0
        // The provider agrees with the cached universe (same id, even a
        // changed chain) — the complaint was a false positive.
        val agreeing = UniverseResolution(
            universe = UniverseList(
                id = "wd:Q900",
                name = "Невідомий всесвіт",
                series = listOf(
                    UniverseSeries(title = "Серія А"),
                    UniverseSeries(title = "Серія Б"),
                    UniverseSeries(title = "Серія В")
                )
            ),
            matchedSeries = UniverseSeries(title = "Серія Б"),
            position = 2
        )
        val resolver = resolverWith(object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                calls++
                return agreeing
            }
        }, store)

        resolver.reportWrongUniverseForBook("b1")

        // The re-resolution ran, but the verdict was "match" — no cache
        // change, no shared write (the complaint is ignored).
        assertEquals(1, calls)
        assertTrue(store.writes.isEmpty())
        assertEquals(listOf("Серія А", "Серія Б"), dao.getSeriesInUniverse("wd:Q900").map { it.title })
        assertEquals(listOf("wd:Q900:2"), dao.getSeriesMembersForWork("w1").map { it.seriesId })
        // The complaint is cleared and the (correct) resolution shows again.
        assertTrue(dao.getCorrectionsForMergeKey("w1").none { it.kind == CorrectionKind.WRONG_UNIVERSE })
        assertEquals("Невідомий всесвіт", resolver.contextOfBook("b1")!!.universeName)
    }

    @Test
    fun `a failing resolve keeps the report - the resolution stays hidden`() = runBlocking {
        seedCachedResolution()
        var calls = 0
        val resolver = resolverWith(object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                calls++
                return null
            }
        })

        resolver.reportWrongUniverseForBook("b1")

        // No verdict from a failure: the report stays, the resolution hides,
        // and the next book open / refresh pass retries.
        assertEquals(1, calls)
        assertTrue(dao.getCorrectionsForMergeKey("w1").any { it.kind == CorrectionKind.WRONG_UNIVERSE })
        assertNull(resolver.contextOfBook("b1"))
        assertEquals(listOf("Серія А", "Серія Б"), dao.getSeriesInUniverse("wd:Q900").map { it.title })
    }
}
