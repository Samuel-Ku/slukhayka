package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec #462 Implementation Decision 6 (#467) — the feed-snapshot seam:
 * the `feed_snapshots` table (v25→v26 migration), the survival of a snapshot
 * across a database reopen, the pure TTL decision (новинки 6 год / каталог
 * 24 год) that gates every network call, and the ADR-0005 tombstone guard on
 * the snapshot-served homepage upsert path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedSnapshotTest {

    // ------------------------------------------------------------------
    // 1. Migration v25 -> v26 (FacetMigrationTest style: a real v25 file)
    // ------------------------------------------------------------------

    @Test
    fun `migration 25 to 26 creates feed_snapshots and keeps v25 rows untouched`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("feed-snapshot-migration-25-26.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("feed-snapshot-migration-25-26.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(25) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)")
                        db.execSQL("INSERT INTO sentinel VALUES ('kept')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val sqlite = helper.writableDatabase

        AudiobookDatabase.MIGRATION_25_26.migrate(sqlite)

        val columns = mutableListOf<Pair<String, String>>() // name to type
        val primaryKeys = mutableListOf<String>()
        sqlite.query("PRAGMA table_info(feed_snapshots)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val typeIdx = cursor.getColumnIndexOrThrow("type")
            val pkIdx = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIdx) to cursor.getString(typeIdx))
                if (cursor.getInt(pkIdx) > 0) primaryKeys.add(cursor.getString(nameIdx))
            }
        }
        assertEquals(
            listOf("sourceId", "feedKey", "pageCursor", "fetchedAt", "cardsJson"),
            columns.map { it.first }
        )
        assertEquals(listOf("TEXT", "TEXT", "TEXT", "INTEGER", "TEXT"), columns.map { it.second })
        // One row per (source, feed, page/cursor) — the composite identity.
        assertEquals(listOf("sourceId", "feedKey", "pageCursor"), primaryKeys)
        // The migration is additive: the v25 data survives untouched.
        sqlite.query("SELECT value FROM sentinel").use { cursor ->
            cursor.moveToFirst()
            assertEquals("kept", cursor.getString(0))
        }
        // Replay is safe (IF NOT EXISTS).
        AudiobookDatabase.MIGRATION_25_26.migrate(sqlite)
        helper.close()
    }

    // ------------------------------------------------------------------
    // Shared fixtures for the store-level and catalog-level gates
    // ------------------------------------------------------------------

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var store: FeedSnapshotStore
    private var now: Long = 1_700_000_000_000L

    /** Fake clock: the TTL decision is pinned, never slept. */
    private val clock: () -> Long = { now }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = FeedSnapshotStore(db.audiobookDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    private fun book(title: String, url: String) = SourceBook(
        title = title,
        author = "Автор",
        url = url,
        sourceId = "sluhayua"
    )

    @Test
    fun `cover parser revision refreshes only affected legacy snapshots`() = runBlocking {
        val dao = db.audiobookDao()
        val key = FeedSnapshotPolicy.FEED_NEW_ARRIVALS
        for (source in listOf("lihtar", "audiobookmp3", "sluhayua")) {
            val old = listOf(book("Книга", "https://example.com/$source").copy(sourceId = source))
            dao.upsertFeedSnapshot(FeedSnapshotEntity(source, key, "", now, FeedSnapshotCodec.encodeBooks(old)))
            if (source == "sluhayua") {
                assertEquals(old, store.freshBooks(source, key))
            } else {
                assertNull("legacy malformed cards need one fresh parse", store.freshBooks(source, key))
                val fixed = old.map { it.copy(coverImageUrl = "https://covers.example/book.jpg") }
                store.saveBooks(source, key, fixed)
                assertEquals(fixed, FeedSnapshotStore(dao, clock).freshBooks(source, key))
            }
        }
    }

    /** A counting fake adapter — the honest «did the network get hit» probe. */
    private class CountingAdapter : SourceAdapter {
        val fetchNewCalls = AtomicInteger(0)
        var page: List<SourceBook> = emptyList()

        /** #622 — makes the next fetch fail like a real transport error. */
        var failNext: Boolean = false

        /** #625 — holds the enumeration in flight so coalescing is observable. */
        var gate: CompletableDeferred<Unit>? = null

        override val sourceId: String = "sluhayua"
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail(title = "", author = "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> {
            fetchNewCalls.incrementAndGet()
            gate?.await()
            if (failNext) throw java.io.IOException("network down")
            return page
        }
    }

    /** #625 — a session-bound source (browser session) whose feed always re-enumerates. */
    private class SessionBoundAdapter : SourceAdapter {
        val fetchNewCalls = AtomicInteger(0)
        var page: List<SourceBook> = emptyList()
        var gate: CompletableDeferred<Unit>? = null

        override val sourceId: String = "cloudflare"
        override val sessionBound: Boolean = true
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail(title = "", author = "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> {
            fetchNewCalls.incrementAndGet()
            gate?.await()
            return page
        }
    }

    // ------------------------------------------------------------------
    // 2. Snapshot survives a restart (close + reopen a new DB instance)
    // ------------------------------------------------------------------

    @Test
    fun `snapshot survives restart - a reopened database serves the same cards`() = runBlocking {
        context.deleteDatabase("feed-snapshot-restart.db")
        val fileDb = Room.databaseBuilder(context, AudiobookDatabase::class.java, "feed-snapshot-restart.db")
            .allowMainThreadQueries()
            .build()
        val firstSession = FeedSnapshotStore(fileDb.audiobookDao(), clock)
        val saved = listOf(book("Книга один", "https://sluhay.com.ua/book-1"), book("Книга два", "https://sluhay.com.ua/book-2"))
        firstSession.saveBooks("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS, saved, pageCursor = "3")
        fileDb.close()

        // "Restart": a brand-new database instance over the same file.
        val reopened = Room.databaseBuilder(context, AudiobookDatabase::class.java, "feed-snapshot-restart.db")
            .allowMainThreadQueries()
            .build()
        try {
            val secondSession = FeedSnapshotStore(reopened.audiobookDao(), clock)
            val restored = secondSession.freshBooks("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS)
            assertEquals(saved, restored)
        } finally {
            reopened.close()
        }
    }

    // ------------------------------------------------------------------
    // 3. The TTL decides whether the network is hit
    // ------------------------------------------------------------------

    @Test
    fun `fresh snapshot answers the feed without the network - stale snapshot refetches`() = runBlocking {
        val adapter = CountingAdapter().apply { page = listOf(book("Нова книга", "https://sluhay.com.ua/new-1")) }
        fun catalog() = SourceCatalog(
            db.audiobookDao(),
            listOf(adapter),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store,
            feedNowMillis = clock
        )

        // First refresh: no snapshot — the network IS hit and what it served
        // is persisted as the snapshot.
        val liveFeeds = catalog().refreshSourceFeeds()
        assertEquals(1, adapter.fetchNewCalls.get())
        assertEquals("Нова книга", liveFeeds.single().books.single().title)

        // Second refresh, a NEW catalog (cold in-memory cache): the fresh
        // snapshot answers — the network is NOT hit.
        val snapshotFeeds = catalog().refreshSourceFeeds()
        assertEquals(1, adapter.fetchNewCalls.get())
        assertEquals("Нова книга", snapshotFeeds.single().books.single().title)

        // Six hours minus one millisecond: still fresh (stale at the EXACT
        // boundary, so anything strictly inside the TTL never fetches).
        now += FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS - 1
        catalog().refreshSourceFeeds()
        assertEquals(1, adapter.fetchNewCalls.get())

        // Past the TTL (and at the exact boundary): the network is hit again,
        // and the new result replaces the snapshot.
        now += 1
        adapter.page = listOf(book("Ще новіша", "https://sluhay.com.ua/new-2"))
        val refetched = catalog().refreshSourceFeeds()
        assertEquals(2, adapter.fetchNewCalls.get())
        assertEquals("Ще новіша", refetched.single().books.single().title)

        // An explicit user refresh bypasses even a fresh snapshot.
        now += 1
        catalog().refreshSourceFeeds(forceRefresh = true)
        assertEquals(3, adapter.fetchNewCalls.get())
    }

    @Test
    fun `catalog feed rides the 24-hour ttl`() = runBlocking {
        val adapter = CountingAdapter().apply { page = listOf(book("Каталожна книга", "https://sluhay.com.ua/cat-1")) }
        val catalog = SourceCatalog(
            db.audiobookDao(),
            listOf(adapter),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store,
            feedNowMillis = clock
        )

        catalog.refreshUnifiedCatalog()
        assertEquals(1, adapter.fetchNewCalls.get())

        // Inside the 24-hour catalog TTL: no network, snapshot served.
        now += FeedSnapshotPolicy.CATALOG_TTL_MS - 1
        val fromSnapshot = SourceCatalog(
            db.audiobookDao(),
            listOf(adapter),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store,
            feedNowMillis = clock
        ).refreshUnifiedCatalog()
        assertEquals(1, adapter.fetchNewCalls.get())
        assertEquals("Каталожна книга", fromSnapshot.single().title)

        // Past the TTL: the network again (a cold instance — the session's
        // in-memory cache rides the real clock, the snapshot rides the fake).
        now += 1
        SourceCatalog(
            db.audiobookDao(),
            listOf(adapter),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store,
            feedNowMillis = clock
        ).refreshUnifiedCatalog()
        assertEquals(2, adapter.fetchNewCalls.get())
    }

    // ------------------------------------------------------------------
    // 4. Tombstones keep blocking reimport from a snapshot (ADR-0005)
    // ------------------------------------------------------------------

    @Test
    fun `tombstoned book is dropped when the homepage is served from the snapshot`() = runBlocking {
        val alive = CatalogBook(id = "soundbooks-alive", title = "Жива книга", author = "Автор", url = "https://sound-books.net/alive/", coverImageUrl = null)
        val dead = CatalogBook(id = "soundbooks-dead", title = "Видалена книга", author = "Автор", url = "https://sound-books.net/dead/", coverImageUrl = null)
        val sections = listOf(
            CatalogSection(
                title = "Новинки",
                books = listOf(alive, dead),
                id = CatalogSectionId.NEW_ARRIVALS
            )
        )
        store.saveHomepage(sections, listOf(CatalogGenre("Фентезі", "https://sound-books.net/fentezi/")))
        // The listener removed one of the books — the durable tombstone must
        // keep blocking it, even when the feed is served from the snapshot.
        db.audiobookDao().insertTombstone(TombstoneEntity(bookId = dead.id, deletedAt = now))

        val catalog = SourceCatalog(
            db.audiobookDao(),
            emptyList(),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store,
            feedNowMillis = clock
        )
        val published = catalog.fetchCatalogSections()

        assertEquals(listOf(alive.id), published.single().books.map { it.id })
        assertFalse(db.audiobookDao().hasAudiobookRow(dead.id))
        assertTrue(db.audiobookDao().hasAudiobookRow(alive.id))
        // The genre nav came from the snapshot too.
        assertEquals(listOf("Фентезі"), catalog.catalogGenres.value.map { it.title })
    }

    @Test
    fun `corrupt snapshot json is a cache miss not a crash`() = runBlocking {
        db.audiobookDao().upsertFeedSnapshot(
            FeedSnapshotEntity(
                sourceId = "sluhayua",
                feedKey = FeedSnapshotPolicy.FEED_NEW_ARRIVALS,
                pageCursor = "",
                fetchedAt = now,
                cardsJson = "{not json at all"
            )
        )
        assertNull(store.freshBooks("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS))
    }

    // ------------------------------------------------------------------
    // 5. Spec-620 (#622) — a failure is not a fresh empty snapshot, and the
    //    SAME live catalog can retry and publish success.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 6. Spec-620 (#625) — the catalogue rides the same refresh module
    // ------------------------------------------------------------------

    @Test
    fun `two equivalent catalogue refreshes share one enumeration`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val adapter = CountingAdapter().apply {
            this.gate = gate
            page = listOf(book("Каталожна", "https://sluhay.com.ua/cat-share"))
        }
        val catalog = SourceCatalog(
            db.audiobookDao(),
            listOf(adapter),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store,
            feedNowMillis = clock
        )

        val first = async { catalog.refreshUnifiedCatalog() }
        while (adapter.fetchNewCalls.get() == 0) delay(1)
        val second = async { catalog.refreshUnifiedCatalog() }
        delay(40)

        assertEquals("equivalent concurrent refreshes share one fetch", 1, adapter.fetchNewCalls.get())
        gate.complete(Unit)
        assertEquals(1, first.await().size)
        assertEquals(1, second.await().size)
        assertEquals(1, adapter.fetchNewCalls.get())
    }

    @Test
    fun `a new browser session discards an older in-flight catalogue result`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val adapter = SessionBoundAdapter().apply {
            this.gate = gate
            page = listOf(book("Стара сесія", "https://cloudflare.example/old"))
        }
        val catalog = SourceCatalog(
            db.audiobookDao(),
            listOf(adapter),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store,
            feedNowMillis = clock
        )

        val old = async { catalog.refreshUnifiedCatalog() }
        while (adapter.fetchNewCalls.get() == 0) delay(1)

        // The listener opens a new browser session while the old attempt is
        // still in flight.
        catalog.noteBrowserSessionOpened()
        adapter.page = listOf(book("Нова сесія", "https://cloudflare.example/new"))
        adapter.gate = null
        val fresh = async { catalog.refreshUnifiedCatalog() }
        assertEquals(listOf("Нова сесія"), fresh.await().map { it.title })

        // Releasing the OLD attempt must not overwrite the new session's snapshot.
        gate.complete(Unit)
        old.await()
        assertEquals(
            listOf("Нова сесія"),
            store.snapshot("cloudflare", FeedSnapshotPolicy.FEED_CATALOG)?.books?.map { it.title }
        )
    }

    @Test
    fun `cancelling one catalogue caller does not stop the other`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val adapter = CountingAdapter().apply {
            this.gate = gate
            page = listOf(book("Спільна", "https://sluhay.com.ua/cat-cancel"))
        }
        val catalog = SourceCatalog(
            db.audiobookDao(),
            listOf(adapter),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store,
            feedNowMillis = clock
        )

        val keeper = async { catalog.refreshUnifiedCatalog() }
        while (adapter.fetchNewCalls.get() == 0) delay(1)
        val leaver = async { catalog.refreshUnifiedCatalog() }
        delay(40)
        leaver.cancel()
        gate.complete(Unit)

        assertEquals(1, keeper.await().size)
        assertEquals("the surviving caller used the one shared fetch", 1, adapter.fetchNewCalls.get())
    }

    @Test
    fun `saving a snapshot atomically replaces the previous rows`() = runBlocking {
        store.saveSnapshot(
            PersistedFeedSnapshot(
                "sluhayua",
                FeedSnapshotPolicy.FEED_NEW_ARRIVALS,
                listOf(book("Стара", "https://sluhay.com.ua/old")),
                now,
                parameters = "limit=20"
            )
        )
        store.saveSnapshot(
            PersistedFeedSnapshot(
                "sluhayua",
                FeedSnapshotPolicy.FEED_NEW_ARRIVALS,
                listOf(book("Нова", "https://sluhay.com.ua/new")),
                now + 1,
                parameters = "limit=20"
            )
        )

        val snapshot = store.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS)
        assertEquals("no rows survive the replace", listOf("Нова"), snapshot?.books?.map { it.title })
        assertEquals("limit=20", snapshot?.parameters)
        assertEquals(now + 1, snapshot?.observedAt)
    }

    @Test
    fun `a failed fetch is not a fresh empty snapshot - the same catalog retries and succeeds`() = runBlocking {
        val adapter = CountingAdapter().apply {
            failNext = true
            page = listOf(book("Після відмови", "https://sluhay.com.ua/retry-1"))
        }
        val catalog = SourceCatalog(
            db.audiobookDao(),
            listOf(adapter),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store,
            feedNowMillis = clock
        )

        // Failure: this refresh honestly carries no row for the source, and
        // nothing was written to memory or Room.
        val failed = catalog.refreshSourceFeeds()
        assertEquals(emptyList<SourceCatalog.SourceNewFeed>(), failed)
        assertNull(store.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS))

        // The very next refresh in the SAME process really hits the source
        // again and publishes the success — no restart, no blocked retry.
        adapter.failNext = false
        val retried = catalog.refreshSourceFeeds()
        assertEquals(2, adapter.fetchNewCalls.get())
        assertEquals("Після відмови", retried.single().books.single().title)
        assertEquals(
            "Після відмови",
            store.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS)?.books?.single()?.title
        )
    }
}
