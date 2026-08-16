package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.SeriesMemberEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.universe.ResolutionProvenance
import com.slukhayka.audiobooks.data.universe.SeriesUniverses
import com.slukhayka.audiobooks.data.universe.SeriesUniverseProvider
import com.slukhayka.audiobooks.data.universe.SharedUniverseStore
import com.slukhayka.audiobooks.data.universe.UniverseRefreshTier
import com.slukhayka.audiobooks.data.universe.UniverseList
import com.slukhayka.audiobooks.data.universe.UniverseResolution
import com.slukhayka.audiobooks.data.universe.UniverseSeries
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
 * Spec-25 (#171) — the lazy series-universe resolution over in-memory Room
 * (the REAL SQL — the book's series fields ride the works/library_entries
 * join, so the fixture seeds the owning rows). External behaviour: a book of
 * a seeded series resolves and caches its universe + membership; an unknown
 * series contributes nothing; re-resolution is a no-op; the series-page
 * resolution caches without a book membership; contextOf reuses the one
 * matcher (URL key wins) and yields the precedes/follows neighbors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SeriesUniversesRoomTest {

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

    private val universes = listOf(
        UniverseList(
            id = "first-law",
            name = "Перший закон",
            series = listOf(
                UniverseSeries(
                    title = "Перший закон",
                    aliases = listOf("Трилогія Першого закону"),
                    urls = listOf("https://4read.org/xfsearch/cikl/pervyj-zakon/")
                ),
                UniverseSeries(
                    title = "Епоха божевілля",
                    aliases = listOf("The Age of Madness"),
                    urls = listOf("https://4read.org/xfsearch/cikl/epoha-bozhevillja/")
                )
            )
        )
    )

    private fun resolver() = SeriesUniverses(dao, universes)

    private suspend fun seedBook(
        seriesTitle: String?,
        seriesUrl: String?,
        seriesIndex: Int?,
        bookId: String = "b1"
    ) = seedBookInto(dao, seriesTitle, seriesUrl, seriesIndex, bookId)

    /** Seeds the same book into any DAO — the second client of the shared
     *  base (spec-26 T6 AC4) uses its own fresh database. */
    private suspend fun seedBookInto(
        dao: AudiobookDao,
        seriesTitle: String?,
        seriesUrl: String?,
        seriesIndex: Int?,
        bookId: String = "b1"
    ) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
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
        dao.upsertLibraryEntry(
            id = bookId,
            workId = "w1",
            isFavorite = false,
            createdAt = 0L,
            downloadProgress = 0f
        )
        dao.upsertWork(
            WorkEntity(
                id = "w1",
                mergeKey = "книга|автор",
                title = "Книга",
                author = "Автор",
                seriesTitle = seriesTitle,
                seriesUrl = seriesUrl,
                seriesIndex = seriesIndex
            )
        )
    }

    // ---------------------------------------------------------------------
    // Resolution writes the cache
    // ---------------------------------------------------------------------

    @Test
    fun `a book of a seeded series resolves and caches its universe and membership`() = runBlocking {
        seedBook(
            seriesTitle = "Епоха божевілля",
            seriesUrl = "https://4read.org/xfsearch/cikl/epoha-bozhevillja/",
            seriesIndex = 1
        )
        val resolver = resolver()

        resolver.resolveForBook("b1")

        // The universe row exists.
        assertEquals("Перший закон", dao.getUniverseById("first-law")!!.name)
        // Both series rows cached, ordered, anchored to the universe.
        val ordered = dao.getSeriesInUniverse("first-law")
        assertEquals(listOf("Перший закон", "Епоха божевілля"), ordered.map { it.title })
        assertEquals(listOf(1, 2), ordered.map { it.positionInUniverse })
        assertTrue(ordered.all { it.universeId == "first-law" })
        // The book's Work is a member of its series at the source's volume index.
        val members = dao.getSeriesMembersForWork("w1")
        assertEquals(1, members.size)
        assertEquals("first-law:2", members[0].seriesId)
        assertEquals(1, members[0].position)
    }

    @Test
    fun `re-resolution is idempotent - same rows, never duplicates`() = runBlocking {
        seedBook("Епоха божевілля", null, seriesIndex = 1)
        val resolver = resolver()

        resolver.resolveForBook("b1")
        resolver.resolveForBook("b1")

        // Two resolutions of the same universe keep ONE row per curated series
        // (the two series, not four) and ONE member row — REPLACE upserts
        // never duplicate.
        assertEquals(2, dao.getSeriesInUniverse("first-law").size)
        assertEquals(1, dao.getSeriesMembersForWork("w1").size)
        assertEquals("Перший закон", dao.getUniverseById("first-law")!!.name)
    }

    // ---------------------------------------------------------------------
    // Unknown series → nothing
    // ---------------------------------------------------------------------

    @Test
    fun `an unseeded series contributes nothing`() = runBlocking {
        seedBook("Невідомий цикл", null, null)
        val resolver = resolver()

        resolver.resolveForBook("b1")

        assertTrue(dao.getSeriesMembersForWork("w1").isEmpty())
        assertNull(dao.getUniverseById("first-law"))
        assertNull(resolver.contextOfBook("b1"))
    }

    @Test
    fun `a book without a series contributes nothing`() = runBlocking {
        seedBook(seriesTitle = null, seriesUrl = null, seriesIndex = null)
        val resolver = resolver()

        resolver.resolveForBook("b1")

        assertNull(dao.getUniverseById("first-law"))
        assertNull(resolver.contextOfBook("b1"))
    }

    // ---------------------------------------------------------------------
    // Series-page resolution (no book) and the context read
    // ---------------------------------------------------------------------

    @Test
    fun `series-page resolution caches the universe without a book membership`() = runBlocking {
        val resolver = resolver()

        resolver.resolveForSeries("Епоха божевілля", "https://4read.org/xfsearch/cikl/epoha-bozhevillja/")

        assertEquals("Перший закон", dao.getUniverseById("first-law")!!.name)
        assertEquals(2, dao.getSeriesInUniverse("first-law").size)
        assertTrue(dao.getSeriesMembersForWork("w1").isEmpty())
    }

    @Test
    fun `contextOf yields the universe position and the precedes neighbor`() = runBlocking {
        val resolver = resolver()
        resolver.resolveForSeries("Епоха божевілля", null)

        val context = resolver.contextOf("Епоха божевілля", null)!!

        assertEquals("Перший закон", context.universeName)
        assertEquals(2, context.position)
        assertEquals(2, context.totalInUniverse)
        assertEquals("Перший закон", context.precedes!!.title)
        assertNull(context.follows)
    }

    @Test
    fun `contextOf follows the url key when the title is unseeded`() = runBlocking {
        val resolver = resolver()
        resolver.resolveForSeries("Епоха божевілля", null)

        // A wrong title but the right series URL — the URL key wins.
        val context = resolver.contextOf(
            "Невідомий цикл",
            "https://4read.org/xfsearch/cikl/epoha-bozhevillja/"
        )!!

        assertEquals("Перший закон", context.universeName)
        assertEquals(2, context.position)
    }

    @Test
    fun `the first series of the universe precedes nothing and follows the second`() = runBlocking {
        val resolver = resolver()
        resolver.resolveForSeries("Перший закон", null)

        val context = resolver.contextOf("Перший закон", null)!!

        assertEquals(1, context.position)
        assertNull(context.precedes)
        assertEquals("Епоха божевілля", context.follows!!.title)
    }

    // ---------------------------------------------------------------------
    // Wikidata fallback (T2): unseeded series resolve through the provider
    // ---------------------------------------------------------------------

    /** The curated-shaped view a Wikidata provider would produce. */
    private val wikidataResolution = UniverseResolution(
        universe = UniverseList(
            id = "wd:Q900",
            name = "Невідомий всесвіт",
            series = listOf(
                UniverseSeries(title = "Серія А"),
                UniverseSeries(title = "Серія Б")
            )
        ),
        matchedSeries = UniverseSeries(title = "Серія Б"),
        position = 2
    )

    private fun wikidataResolver(
        resolution: UniverseResolution? = wikidataResolution,
        onCall: () -> Unit = {},
        // Spec-26 T7: the tier rule is injectable; the tests use a flat 10s
        // tier so "fresh" vs "stale" is a plain clock comparison.
        tierTtl: (Boolean, Int?, Int) -> Long = { _, _, _ -> 10_000L },
        now: () -> Long = System::currentTimeMillis
    ): SeriesUniverses = SeriesUniverses(
        dao,
        universes,
        wikidata = object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                onCall()
                return resolution
            }
        },
        tierTtlMillis = tierTtl,
        now = now
    )

    @Test
    fun `the wikidata fallback resolves and caches an unseeded series`() = runBlocking {
        seedBook("Невідомий цикл", null, seriesIndex = 2)
        val resolver = wikidataResolver()

        resolver.resolveForBook("b1")

        // The provider's universe and ordered series rows land in the cache.
        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
        val ordered = dao.getSeriesInUniverse("wd:Q900")
        assertEquals(listOf("Серія А", "Серія Б"), ordered.map { it.title })
        assertEquals(listOf(1, 2), ordered.map { it.positionInUniverse })
        // The book's Work is a member of the matched series at its volume index.
        val members = dao.getSeriesMembersForWork("w1")
        assertEquals(listOf("wd:Q900:2"), members.map { it.seriesId })
        assertEquals(2, members[0].position)
        // The cached context surfaces exactly like a curated one.
        val context = resolver.contextOfBook("b1")!!
        assertEquals("Невідомий всесвіт", context.universeName)
        assertEquals(2, context.position)
        assertEquals(2, context.totalInUniverse)
        assertEquals("Серія А", context.precedes!!.title)
    }

    @Test
    fun `curated wins - the provider is never consulted for a seeded series`() = runBlocking {
        seedBook("Епоха божевілля", null, seriesIndex = 1)
        var calls = 0
        val resolver = wikidataResolver(onCall = { calls++ })

        resolver.resolveForBook("b1")

        // The curated asset resolved the book — the provider was never asked.
        assertEquals(0, calls)
        assertEquals("Перший закон", dao.getUniverseById("first-law")!!.name)
        assertNull(dao.getUniverseById("wd:Q900"))
        assertEquals(listOf("first-law:2"), dao.getSeriesMembersForWork("w1").map { it.seriesId })
    }

    @Test
    fun `the membership gate prevents a second provider resolution`() = runBlocking {
        seedBook("Невідомий цикл", null, seriesIndex = 1)
        var calls = 0
        val resolver = wikidataResolver(onCall = { calls++ })

        resolver.resolveForBook("b1")
        resolver.resolveForBook("b1")

        // The first resolution cached the membership; the second is gated on
        // the cache — the provider is consulted exactly once.
        assertEquals(1, calls)
        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
        assertEquals(1, dao.getSeriesMembersForWork("w1").size)
    }


    // ---------------------------------------------------------------------
    // TTL: stale memberships re-resolve instead of persisting forever
    // ---------------------------------------------------------------------

    @Test
    fun `a stale membership re-resolves through the provider and refreshes the stamp`() = runBlocking {
        seedBook("Невідомий цикл", null, seriesIndex = 1)
        var currentTime = 1_000_000L
        var calls = 0
        val resolver = wikidataResolver(onCall = { calls++ }, now = { currentTime })

        resolver.resolveForBook("b1")
        assertEquals(1, calls)
        assertEquals(1_000_000L, dao.getSeriesMembersForWork("w1")[0].resolvedAt)

        // Within the TTL — the membership is fresh, no re-resolution.
        currentTime += 5_000
        resolver.resolveForBook("b1")
        assertEquals(1, calls)

        // Past the TTL — the provider is consulted again and the stamp
        // refreshes; the same membership row is REPLACEd, never duplicated.
        currentTime += 10_000
        resolver.resolveForBook("b1")
        assertEquals(2, calls)
        assertEquals(1_015_000L, dao.getSeriesMembersForWork("w1")[0].resolvedAt)
        assertEquals(1, dao.getSeriesMembersForWork("w1").size)
        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
    }

    @Test
    fun `a membership without a stamp counts as stale and refreshes`() = runBlocking {
        seedBook("Невідомий цикл", null, seriesIndex = 1)
        var calls = 0
        val resolver = wikidataResolver(onCall = { calls++ }, now = { 1_000_000L })
        // A pre-TTL row (migration leftovers): a membership with a NULL stamp.
        dao.upsertSeriesMember(
            SeriesMemberEntity(workId = "w1", seriesId = "wd:Q900:2", position = 1, resolvedAt = null)
        )

        resolver.resolveForBook("b1")

        // Null is unknown → stale → the provider refreshes and stamps the row.
        assertEquals(1, calls)
        assertEquals(1_000_000L, dao.getSeriesMembersForWork("w1")[0].resolvedAt)
    }

    @Test
    fun `a failing re-resolution leaves the stale row untouched`() = runBlocking {
        seedBook("Невідомий цикл", null, seriesIndex = 1)
        var currentTime = 1_000_000L
        var calls = 0
        val resolver = wikidataResolver(
            resolution = null, // the provider fails on the re-resolution
            onCall = { calls++ },
            now = { currentTime }
        )
        // A stale membership (pre-TTL) already in the cache.
        dao.upsertSeriesMember(
            SeriesMemberEntity(workId = "w1", seriesId = "wd:Q900:2", position = 1, resolvedAt = 900_000L)
        )

        resolver.resolveForBook("b1")

        // The failed refresh contributes nothing — the stale row stays, and
        // the next book open retries.
        assertEquals(1, calls)
        assertEquals(900_000L, dao.getSeriesMembersForWork("w1")[0].resolvedAt)
    }

    // ---------------------------------------------------------------------
    // Shared store read path (spec-26 T5): Room → Firestore → Wikidata
    // ---------------------------------------------------------------------

    /** A canned shared store: known workIds answer, everything else misses. */
    private fun sharedStore(
        vararg resolutions: Pair<String, UniverseResolution?>,
        onCall: () -> Unit = {}
    ): SharedUniverseStore = object : SharedUniverseStore {
        private val map = resolutions.toMap()
        override suspend fun getResolution(workId: String): UniverseResolution? {
            onCall()
            return map[workId]
        }
        override suspend fun putResolution(
            workId: String,
            resolution: UniverseResolution,
            provenance: com.slukhayka.audiobooks.data.universe.ResolutionProvenance
        ) = Unit
    }

    /**
     * A shared store that RECORDS write-backs (spec-26 T6): writes land in
     * a map (so a second client with the same store instance reads them)
     * and in the [writes] log; [failWrites] makes a write throw — the
     * silent-failure contract.
     */
    private class RecordingStore : SharedUniverseStore {
        val writes = mutableListOf<Triple<String, UniverseResolution, com.slukhayka.audiobooks.data.universe.ResolutionProvenance>>()
        private val docs = mutableMapOf<String, UniverseResolution>()
        var failWrites = false

        override suspend fun getResolution(workId: String): UniverseResolution? = docs[workId]

        override suspend fun putResolution(
            workId: String,
            resolution: UniverseResolution,
            provenance: com.slukhayka.audiobooks.data.universe.ResolutionProvenance
        ) {
            if (failWrites) throw IllegalStateException("offline")
            writes += Triple(workId, resolution, provenance)
            docs[workId] = resolution
        }
    }

    private fun sharedResolver(
        shared: SharedUniverseStore?,
        onWikidataCall: () -> Unit = {},
        // Spec-26 T7: the tier rule is injectable; the tests use a flat 10s
        // tier so "fresh" vs "stale" is a plain clock comparison.
        tierTtl: (Boolean, Int?, Int) -> Long = { _, _, _ -> 10_000L },
        now: () -> Long = System::currentTimeMillis
    ): SeriesUniverses = SeriesUniverses(
        dao,
        universes,
        wikidata = object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                onWikidataCall()
                return wikidataResolution
            }
        },
        sharedStore = shared,
        tierTtlMillis = tierTtl,
        now = now
    )

    @Test
    fun `a shared-store hit mirrors into the room cache and skips wikidata`() = runBlocking {
        seedBook("Невідомий цикл", null, seriesIndex = 2)
        var sharedCalls = 0
        var wikidataCalls = 0
        val resolver = sharedResolver(
            shared = sharedStore("w1" to wikidataResolution, onCall = { sharedCalls++ }),
            onWikidataCall = { wikidataCalls++ }
        )

        resolver.resolveForBook("b1")

        // The shared hit populated the cache exactly like a fresh resolution.
        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
        assertEquals(listOf("Серія А", "Серія Б"), dao.getSeriesInUniverse("wd:Q900").map { it.title })
        assertEquals(listOf("wd:Q900:2"), dao.getSeriesMembersForWork("w1").map { it.seriesId })
        // The hit never paid for a Wikidata resolution.
        assertEquals(1, sharedCalls)
        assertEquals(0, wikidataCalls)
        // The mirrored cache is the instant offline read model.
        assertEquals("Невідомий всесвіт", resolver.contextOfBook("b1")!!.universeName)
    }

    @Test
    fun `a shared-store miss falls through to wikidata`() = runBlocking {
        seedBook("Невідомий цикл", null, seriesIndex = 2)
        var wikidataCalls = 0
        val resolver = sharedResolver(
            shared = sharedStore("other-work" to wikidataResolution), // miss for w1
            onWikidataCall = { wikidataCalls++ }
        )

        resolver.resolveForBook("b1")

        assertEquals(1, wikidataCalls)
        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
        assertEquals(listOf("wd:Q900:2"), dao.getSeriesMembersForWork("w1").map { it.seriesId })
    }

    @Test
    fun `a shared-store failure degrades silently to wikidata`() = runBlocking {
        // The seam contract: a store that fails (offline, timeout, corrupt
        // document) returns null — the read falls through exactly like a miss.
        seedBook("Невідомий цикл", null, seriesIndex = 1)
        var wikidataCalls = 0
        val resolver = sharedResolver(
            shared = object : SharedUniverseStore {
                override suspend fun getResolution(workId: String): UniverseResolution? = null
                override suspend fun putResolution(
                    workId: String,
                    resolution: UniverseResolution,
                    provenance: com.slukhayka.audiobooks.data.universe.ResolutionProvenance
                ) = Unit
            },
            onWikidataCall = { wikidataCalls++ }
        )

        resolver.resolveForBook("b1")

        assertEquals(1, wikidataCalls)
        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
    }

    @Test
    fun `a fresh room membership skips the shared store`() = runBlocking {
        // The Room layer is first: a membership resolved within the TTL is
        // the answer — neither the shared store nor Wikidata is consulted.
        seedBook("Невідомий цикл", null, seriesIndex = 1)
        dao.upsertSeriesMember(
            SeriesMemberEntity(workId = "w1", seriesId = "wd:Q900:2", position = 1, resolvedAt = 1_000_000L)
        )
        var sharedCalls = 0
        var wikidataCalls = 0
        val resolver = sharedResolver(
            shared = sharedStore("w1" to wikidataResolution, onCall = { sharedCalls++ }),
            onWikidataCall = { wikidataCalls++ },
            now = { 1_005_000L }
        )

        resolver.resolveForBook("b1")

        assertEquals(0, sharedCalls)
        assertEquals(0, wikidataCalls)
    }

    @Test
    fun `curated wins - the shared store is not consulted for a seeded series`() = runBlocking {
        seedBook("Епоха божевілля", null, seriesIndex = 1)
        var sharedCalls = 0
        val resolver = sharedResolver(
            shared = sharedStore("w1" to wikidataResolution, onCall = { sharedCalls++ })
        )

        resolver.resolveForBook("b1")

        assertEquals(0, sharedCalls)
        assertEquals("Перший закон", dao.getUniverseById("first-law")!!.name)
        assertNull(dao.getUniverseById("wd:Q900"))
    }

    @Test
    fun `without a shared store the read path is unchanged`() = runBlocking {
        // AC1: no Firebase keys → sharedStore null → exactly the pre-Firestore
        // path (the existing wikidataResolver has no shared store).
        seedBook("Невідомий цикл", null, seriesIndex = 1)
        var wikidataCalls = 0
        val resolver = wikidataResolver(onCall = { wikidataCalls++ })

        resolver.resolveForBook("b1")

        assertEquals(1, wikidataCalls)
        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
    }

    // ---------------------------------------------------------------------
    // Write-back (spec-26 T6): a Wikidata resolution is written to the
    // shared store with provenance; the curated path never writes
    // ---------------------------------------------------------------------

    @Test
    fun `a wikidata resolution writes back to the shared store with provenance`() = runBlocking {
        seedBook("Невідомий цикл", null, seriesIndex = 2)
        val store = RecordingStore()
        val resolver = sharedResolver(shared = store, now = { 1_000_000L })

        resolver.resolveForBook("b1")

        // AC1: the write-back carries the provenance — source=wikidata,
        // author-verified (the provider returns only P50-verified works),
        // resolvedAt from the injected clock.
        assertEquals(1, store.writes.size)
        val (workId, resolution, provenance) = store.writes[0]
        assertEquals("w1", workId)
        assertEquals(wikidataResolution, resolution)
        assertEquals(ResolutionProvenance.SOURCE_WIKIDATA, provenance.source)
        assertTrue(provenance.authorVerified)
        assertEquals(1_000_000L, provenance.resolvedAt)
        // The local cache persisted as before.
        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
    }

    @Test
    fun `a failing write-back is silent and the room cache stands`() = runBlocking {
        // AC5: the write must never surface to the caller — the local cache
        // already persisted and must not suffer.
        seedBook("Невідомий цикл", null, seriesIndex = 2)
        val store = RecordingStore().apply { failWrites = true }
        val resolver = sharedResolver(shared = store)

        resolver.resolveForBook("b1") // must not throw

        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
        assertEquals(listOf("wd:Q900:2"), dao.getSeriesMembersForWork("w1").map { it.seriesId })
    }

    @Test
    fun `curated resolutions do not write back - the seed owns the curated path`() = runBlocking {
        // The curated asset pours into the shared base via the first-launch
        // seed, not per-book — a curated match writes nothing.
        seedBook("Епоха божевілля", null, seriesIndex = 1)
        val store = RecordingStore()
        val resolver = sharedResolver(shared = store)

        resolver.resolveForBook("b1")

        assertTrue(store.writes.isEmpty())
        assertEquals("Перший закон", dao.getUniverseById("first-law")!!.name)
    }

    @Test
    fun `the second client reads what the first wrote back`() = runBlocking {
        // AC4: client A misses → resolves via Wikidata → writes back; client
        // B, a FRESH database over the SAME shared base and NO Wikidata
        // provider, reads the written-back resolution instead of re-resolving.
        seedBook("Невідомий цикл", null, seriesIndex = 2)
        val store = RecordingStore()
        val clientA = sharedResolver(shared = store, now = { 1_000_000L })
        clientA.resolveForBook("b1")
        assertEquals(1, store.writes.size)

        val dbB = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val daoB = dbB.audiobookDao()
            seedBookInto(daoB, "Невідомий цикл", null, seriesIndex = 2)
            val clientB = SeriesUniverses(
                daoB,
                universes,
                wikidata = null, // must never be needed — the shared base answers
                sharedStore = store,
                tierTtlMillis = { _, _, _ -> 10_000L },
                now = { 1_000_000L }
            )

            clientB.resolveForBook("b1")

            assertEquals("Невідомий всесвіт", daoB.getUniverseById("wd:Q900")!!.name)
            assertEquals(listOf("wd:Q900:2"), daoB.getSeriesMembersForWork("w1").map { it.seriesId })
        } finally {
            dbB.close()
        }
    }

    // ---------------------------------------------------------------------
    // Tiered refresh (spec-26 T7): the P577 year rides the resolution into
    // the cache, and the work-row path re-resolves without a library book
    // ---------------------------------------------------------------------

    @Test
    fun `the P577 publication year persists to the series rows`() = runBlocking {
        seedBook("Невідомий цикл", null, seriesIndex = 2)
        val resolutionWithYear = UniverseResolution(
            universe = UniverseList(
                id = "wd:Q900",
                name = "Невідомий всесвіт",
                series = listOf(
                    UniverseSeries(title = "Серія А", publicationYear = 2019),
                    UniverseSeries(title = "Серія Б", publicationYear = 2021)
                )
            ),
            matchedSeries = UniverseSeries(title = "Серія Б", publicationYear = 2021),
            position = 2
        )
        val resolver = SeriesUniverses(
            dao,
            universes,
            wikidata = object : SeriesUniverseProvider {
                override suspend fun resolve(bookTitle: String, bookAuthor: String) = resolutionWithYear
            },
            tierTtlMillis = { _, _, _ -> 10_000L },
            now = { 1_000_000L }
        )

        resolver.resolveForBook("b1")

        // The tier rule's age signal is in the cache, per series row.
        assertEquals(listOf(2019, 2021), dao.getSeriesInUniverse("wd:Q900").map { it.publicationYear })
    }

    @Test
    fun `resolveForWork resolves straight from the work row without a book`() = runBlocking {
        // The background refresh pass's path (spec-26 T7): only the work row
        // exists — no audiobook, no library entry.
        dao.upsertWork(
            WorkEntity(
                id = "w2",
                mergeKey = "невідомий цикл|автор",
                title = "Книга",
                author = "Автор",
                seriesTitle = "Невідомий цикл",
                seriesUrl = null,
                seriesIndex = 1
            )
        )
        val resolver = wikidataResolver()

        resolver.resolveForWork("w2")

        assertEquals("Невідомий всесвіт", dao.getUniverseById("wd:Q900")!!.name)
        assertEquals(listOf("wd:Q900:2"), dao.getSeriesMembersForWork("w2").map { it.seriesId })
        assertEquals(1, dao.getSeriesMembersForWork("w2")[0].position)
    }

    @Test
    fun `the tier gate re-resolves a hot membership at 8 days but not a cold one`() = runBlocking {
        // The real tier rule (spec-26 T7): a tail + young series refreshes
        // fastest. Exercised against pre-seeded cache state so the signals
        // (chain-tail position, P577 year) are exact. Both cases use
        // resolveForWork — the pass's path — with the clock 8 days past the
        // membership stamp.
        val t0 = 1_700_000_000_000L // 2023-11-14 UTC
        val eightDays = 8L * 24 * 60 * 60 * 1000

        suspend fun seedWork(workId: String, seriesTitle: String) {
            dao.upsertWork(
                WorkEntity(
                    id = workId,
                    mergeKey = "$seriesTitle|автор",
                    title = "Книга",
                    author = "Автор",
                    seriesTitle = seriesTitle,
                    seriesUrl = null,
                    seriesIndex = 1
                )
            )
        }

        suspend fun seedUniverse(universeId: String, tailYear: Int) {
            dao.upsertSeries(
                com.slukhayka.audiobooks.data.db.SeriesEntity(
                    id = "$universeId:1", title = "Серія А", universeId = universeId,
                    positionInUniverse = 1, publicationYear = 2000
                )
            )
            dao.upsertSeries(
                com.slukhayka.audiobooks.data.db.SeriesEntity(
                    id = "$universeId:2", title = "Серія Б", universeId = universeId,
                    positionInUniverse = 2, publicationYear = tailYear
                )
            )
        }

        fun resolverWith(provider: SeriesUniverseProvider, nowMillis: Long) = SeriesUniverses(
            dao,
            universes,
            wikidata = provider,
            tierTtlMillis = UniverseRefreshTier::tierTtlMillis,
            now = { nowMillis }
        )

        // HOT: the matched series is the tail (pos 2 of 2) and young (2021)
        // → 7-day tier → 8 days is stale → the provider re-resolves.
        seedWork("w1", "Невідомий цикл")
        seedUniverse("wd:Q900", tailYear = 2021)
        dao.upsertSeriesMember(
            SeriesMemberEntity(workId = "w1", seriesId = "wd:Q900:2", position = 1, resolvedAt = t0)
        )
        var hotCalls = 0
        resolverWith(object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                hotCalls++
                return wikidataResolution
            }
        }, t0 + eightDays).resolveForWork("w1")
        assertEquals(1, hotCalls)

        // COLD: the matched series is the middle (pos 1 of 2) and old (2000)
        // → 180-day floor → 8 days is fresh → nothing re-resolves.
        seedWork("w3", "Невідомий цикл 3")
        seedUniverse("wd:Q901", tailYear = 2000)
        dao.upsertSeriesMember(
            SeriesMemberEntity(workId = "w3", seriesId = "wd:Q901:1", position = 1, resolvedAt = t0)
        )
        var coldCalls = 0
        resolverWith(object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                coldCalls++
                return wikidataResolution
            }
        }, t0 + eightDays).resolveForWork("w3")
        assertEquals(0, coldCalls)
    }
}
