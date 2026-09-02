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

    /** A counting fake adapter — the honest «did the network get hit» probe. */
    private class CountingAdapter : SourceAdapter {
        val fetchNewCalls = AtomicInteger(0)
        var page: List<SourceBook> = emptyList()

        override val sourceId: String = "sluhayua"
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail(title = "", author = "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> {
            fetchNewCalls.incrementAndGet()
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
            feedSnapshotStore = store
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
            feedSnapshotStore = store
        )

        catalog.refreshUnifiedCatalog()
        assertEquals(1, adapter.fetchNewCalls.get())

        // Inside the 24-hour catalog TTL: no network, snapshot served.
        now += FeedSnapshotPolicy.CATALOG_TTL_MS - 1
        val fromSnapshot = SourceCatalog(
            db.audiobookDao(),
            listOf(adapter),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store
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
            feedSnapshotStore = store
        ).refreshUnifiedCatalog()
        assertEquals(2, adapter.fetchNewCalls.get())
    }

    // ------------------------------------------------------------------
    // 4. Tombstones keep blocking reimport from a snapshot (ADR-0005)
    // ------------------------------------------------------------------

    @Test
    fun `tombstoned book is dropped when the homepage is served from the snapshot`() = runBlocking {
        val alive = CatalogBook(id = "4read-alive", title = "Жива книга", author = "Автор", url = "https://4read.org/alive/", coverImageUrl = null)
        val dead = CatalogBook(id = "4read-dead", title = "Видалена книга", author = "Автор", url = "https://4read.org/dead/", coverImageUrl = null)
        val sections = listOf(
            CatalogSection(
                title = "Новинки",
                books = listOf(alive, dead),
                id = CatalogSectionId.NEW_ARRIVALS
            )
        )
        store.saveHomepage(sections, listOf(CatalogGenre("Фентезі", "https://4read.org/fentezi/")))
        // The listener removed one of the books — the durable tombstone must
        // keep blocking it, even when the feed is served from the snapshot.
        db.audiobookDao().insertTombstone(TombstoneEntity(bookId = dead.id, deletedAt = now))

        val catalog = SourceCatalog(
            db.audiobookDao(),
            emptyList(),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            feedSnapshotStore = store
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
}
