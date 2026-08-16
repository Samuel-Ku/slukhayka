package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.db.AudiobookEntity
import com.example.data.db.WorkEntity
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
        onCall: () -> Unit = {}
    ): SeriesUniverses = SeriesUniverses(
        dao,
        universes,
        wikidata = object : SeriesUniverseProvider {
            override suspend fun resolve(bookTitle: String, bookAuthor: String): UniverseResolution? {
                onCall()
                return resolution
            }
        }
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
        val resolver = wikidataResolver(onCall = { calls++ }, ttlMillis = 10_000L, now = { currentTime })

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
        val resolver = wikidataResolver(onCall = { calls++ }, ttlMillis = 10_000L, now = { 1_000_000L })
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
            ttlMillis = 10_000L,
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
    }

    private fun sharedResolver(
        shared: SharedUniverseStore?,
        onWikidataCall: () -> Unit = {},
        ttlMillis: Long = 10_000L,
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
        ttlMillis = ttlMillis,
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
            ttlMillis = 10_000L,
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
}
