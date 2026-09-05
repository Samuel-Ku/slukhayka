package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.metadata.PopularityAssertionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #485 — the persistent layer beneath the live collection shelves: the
 * `popularity_assertions` table (v26→v27 migration), provenance-bearing
 * write/read, the pure expiry rule and restart survival (a fresh Room
 * instance on the same file sees the rows).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PopularityAssertionStoreTest {
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
    fun tearDown() = db.close()

    // ------------------------------------------------------------------
    // Write / read with provenance
    // ------------------------------------------------------------------

    @Test
    fun `rank signals are written and read back with full provenance`() = runBlocking {
        dao.upsertPopularityAssertions(
            listOf(
                PopularityAssertionEntity(
                    id = "pop:soundbooks-top:кобзар|шевченко",
                    kind = PopularityAssertionEntity.KIND_RANK,
                    mergeKey = "кобзар|шевченко",
                    rawValue = "12",
                    sourceId = "soundbooks-top",
                    observedAt = 1_000L
                ),
                PopularityAssertionEntity(
                    id = "pop:sluhayua-popular:тихий|дім",
                    kind = PopularityAssertionEntity.KIND_RANK,
                    mergeKey = "тихий|дім",
                    rawValue = "3",
                    sourceId = "sluhayua-popular",
                    observedAt = 2_000L
                )
            )
        )

        val rows = dao.popularityAssertions(kind = PopularityAssertionEntity.KIND_RANK)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.sourceId == it.sourceId && it.rawValue.isNotBlank() && it.observedAt > 0 })
        assertEquals(setOf("soundbooks-top", "sluhayua-popular"), rows.map { it.sourceId }.toSet())
    }

    @Test
    fun `rating signal is written with its claimed value`() = runBlocking {
        dao.upsertPopularityAssertions(
            listOf(
                PopularityAssertionEntity(
                    id = "rating:4read:кобзар|шевченко",
                    kind = PopularityAssertionEntity.KIND_RATING,
                    mergeKey = "кобзар|шевченко",
                    rawValue = "4.5",
                    sourceId = "4read",
                    observedAt = 5_000L
                )
            )
        )
        val rows = dao.popularityAssertions(kind = PopularityAssertionEntity.KIND_RATING)
        assertEquals(1, rows.size)
        assertEquals("4.5", rows.single().rawValue)
        assertEquals("4read", rows.single().sourceId)
    }

    @Test
    fun `a re-observed signal replaces its row and refreshes the timestamp`() = runBlocking {
        val row = PopularityAssertionEntity(
            id = "pop:soundbooks-top:кобзар|шевченко",
            kind = PopularityAssertionEntity.KIND_RANK,
            mergeKey = "кобзар|шевченко",
            rawValue = "12",
            sourceId = "soundbooks-top",
            observedAt = 1_000L
        )
        dao.upsertPopularityAssertions(listOf(row))
        dao.upsertPopularityAssertions(listOf(row.copy(rawValue = "5", observedAt = 9_000L)))

        val rows = dao.popularityAssertions(kind = PopularityAssertionEntity.KIND_RANK)
        assertEquals(1, rows.size)
        assertEquals("5", rows.single().rawValue)
        assertEquals(9_000L, rows.single().observedAt)
    }

    @Test
    fun `signals survive a database reopen`() {
        // In-memory rows die with the instance — persistence is proven on a
        // FILE database: write, close, reopen, read.
        val name = "popularity-reopen-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            first.audiobookDao().upsertPopularityAssertions(
                listOf(
                    PopularityAssertionEntity(
                        id = "pop:soundbooks-top:кобзар|шевченко",
                        kind = PopularityAssertionEntity.KIND_RANK,
                        mergeKey = "кобзар|шевченко",
                        rawValue = "12",
                        sourceId = "soundbooks-top",
                        observedAt = 1_000L
                    )
                )
            )
        }
        first.close()

        val reopened = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val survived = runBlocking {
                reopened.audiobookDao().popularityAssertions(PopularityAssertionEntity.KIND_RANK)
            }
            assertEquals(listOf("pop:soundbooks-top:кобзар|шевченко"), survived.map { it.id })
            assertEquals("soundbooks-top", survived.single().sourceId)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `stale and fresh rows are distinguishable by the pure expiry rule`() = runBlocking {
        val now = 1_000_000L
        dao.upsertPopularityAssertions(
            listOf(
                PopularityAssertionEntity(
                    id = "pop:a:свіжий|автор",
                    kind = PopularityAssertionEntity.KIND_RANK,
                    mergeKey = "свіжий|автор",
                    rawValue = "1",
                    sourceId = "a",
                    observedAt = now - 1
                ),
                PopularityAssertionEntity(
                    id = "pop:a:старий|автор",
                    kind = PopularityAssertionEntity.KIND_RANK,
                    mergeKey = "старий|автор",
                    rawValue = "2",
                    sourceId = "a",
                    observedAt = now - PopularityAssertionPolicy.POPULARITY_TTL_MS - 1
                )
            )
        )
        val fresh = dao.popularityAssertions(kind = PopularityAssertionEntity.KIND_RANK)
            .filter { PopularityAssertionPolicy.isFresh(it.observedAt, now) }
        assertEquals(listOf("свіжий|автор"), fresh.map { it.mergeKey })
    }

    // ------------------------------------------------------------------
    // Migration v27 -> v28
    // ------------------------------------------------------------------

    @Test
    fun `migration 27 to 28 creates popularity_assertions and keeps v27 rows untouched`() {
        context.deleteDatabase("popularity-migration-27-28.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("popularity-migration-27-28.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(27) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE works (id TEXT NOT NULL PRIMARY KEY, addedAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE person_bookmarks (id TEXT NOT NULL PRIMARY KEY, lastNotifiedCount INTEGER NOT NULL DEFAULT 0)")
                        db.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)")
                        db.execSQL("INSERT INTO sentinel VALUES ('kept')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val sqlite = helper.writableDatabase
        val migrated = AudiobookDatabase.MIGRATION_27_28.migrate(sqlite)

        sqlite.query("SELECT value FROM sentinel").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("kept", cursor.getString(0))
        }
        // The new table exists with the Room-expected shape (a query must not throw).
        sqlite.query(
            "SELECT id, kind, mergeKey, rawValue, sourceId, observedAt FROM popularity_assertions LIMIT 1"
        ).use { it.moveToFirst() }
        assertEquals(28, AudiobookDatabase.MIGRATION_27_28.endVersion)
        helper.close()
    }
}
