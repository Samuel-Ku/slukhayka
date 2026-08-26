package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.duration.DurationBuckets
import com.slukhayka.audiobooks.data.metadata.DurationSanity
import com.slukhayka.audiobooks.data.metadata.FacetDurationBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FacetMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `migration 21 to 22 adds all facet shapes and backfills real genres without changing domain rows`() {
        context.deleteDatabase("facet-migration-21-22.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("facet-migration-21-22.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE works (id TEXT NOT NULL PRIMARY KEY, mergeKey TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, coverImageUrl TEXT, addedAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE editions (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, language TEXT NOT NULL, narrator TEXT NOT NULL, totalChapters INTEGER NOT NULL, totalDurationSeconds INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, genre TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE library_entries (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE work_sources (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE series_members (workId TEXT NOT NULL, seriesId TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)")
                        db.execSQL("INSERT INTO works VALUES ('w1','key','Назва','Автор',NULL,NULL,NULL,NULL,7)")
                        db.execSQL("INSERT INTO editions VALUES ('e1','w1','uk','Диктор',12,3600)")
                        db.execSQL("INSERT INTO editions VALUES ('e2','w2','uk','Диктор 2',3,${DurationBuckets.FABRICATED_LEGACY_SECONDS})")
                        db.execSQL("INSERT INTO editions VALUES ('e3','w3','uk','Диктор 3',3,${DurationSanity.MAX_PLAUSIBLE_SECONDS + 1L})")
                        db.execSQL("INSERT INTO audiobooks VALUES ('a1',' ФАНТАСТИКА · Наукова фантастика / Фентезі ')")
                        db.execSQL("INSERT INTO audiobooks VALUES ('a2','   ')")
                        db.execSQL("INSERT INTO audiobooks VALUES ('a3','Каталог')")
                        db.execSQL("INSERT INTO library_entries VALUES ('a1','w1')")
                        db.execSQL("INSERT INTO library_entries VALUES ('a2','w2')")
                        db.execSQL("INSERT INTO library_entries VALUES ('a3','w3')")
                        db.execSQL("INSERT INTO sentinel VALUES ('kept')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val sqlite = helper.writableDatabase

        AudiobookDatabase.MIGRATION_21_22.migrate(sqlite)

        listOf(
            "genre_facets", "work_facets", "work_facet_series", "work_genres", "genre_assertion_states", "genre_assertions",
            "edition_facets", "author_facets", "author_aliases"
        ).forEach { assertTrue("missing $it", tableExists(sqlite, it)) }
        assertTrue(indexExists(sqlite, "index_work_genres_genreId"))
        assertTrue(indexExists(sqlite, "index_work_genres_workId_sourceId"))
        assertTrue(indexExists(sqlite, "index_work_facet_series_seriesId"))
        assertTrue(indexExists(sqlite, "index_edition_facets_durationBucketId"))
        assertTrue(indexExists(sqlite, "index_author_aliases_normalizedAlias"))
        assertTrue(indexExists(sqlite, "index_genre_assertions_assertionId"))
        assertTrue(columnExists(sqlite, "edition_facets", "availabilityAvailable"))
        assertTrue(columnExists(sqlite, "edition_facets", "availabilityObservedAtMillis"))
        assertTrue(columnExists(sqlite, "edition_facets", "availabilityTtlSeconds"))
        assertTrue(columnExists(sqlite, "work_genres", "sourceId"))
        assertTrue(columnExists(sqlite, "genre_assertions", "assertionId"))
        assertTrue(!columnExists(sqlite, "work_facets", "availableSourceCount"))
        assertTrue(!columnExists(sqlite, "work_facets", "availabilityObservedAt"))

        sqlite.query("SELECT genreId FROM work_genres WHERE workId='w1' ORDER BY genreId").use { cursor ->
            val ids = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            assertEquals(listOf("fantasy", "science-fiction"), ids)
        }
        sqlite.query("SELECT rawText, sourceId FROM genre_assertions WHERE workId='w1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(" ФАНТАСТИКА · Наукова фантастика / Фентезі ", cursor.getString(0))
            assertEquals("legacy:audiobooks", cursor.getString(1))
        }
        sqlite.query("SELECT DISTINCT sourceId FROM work_genres WHERE workId='w1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy:audiobooks", cursor.getString(0))
        }
        sqlite.query("SELECT documentUpdatedAt FROM genre_assertion_states WHERE workId='w1' AND sourceId='legacy:audiobooks'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
        sqlite.query("SELECT genre FROM audiobooks WHERE id='a1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(" ФАНТАСТИКА · Наукова фантастика / Фентезі ", cursor.getString(0))
        }
        sqlite.query("SELECT COUNT(*) FROM work_genres WHERE workId IN ('w2','w3')").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        sqlite.query("SELECT value FROM sentinel").use { cursor ->
            cursor.moveToFirst()
            assertEquals("kept", cursor.getString(0))
        }
        sqlite.query("SELECT narrator, totalDurationSeconds FROM editions WHERE id='e1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Диктор", cursor.getString(0))
            assertEquals(3600L, cursor.getLong(1))
        }
        sqlite.query("SELECT availabilityAvailable, availabilityObservedAtMillis, availabilityTtlSeconds FROM edition_facets WHERE editionId='e1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
        sqlite.query("SELECT durationSeconds, durationBucketId FROM edition_facets WHERE editionId='e1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3_600L, cursor.getLong(0))
            assertEquals(FacetDurationBucket.UNDER_FIVE_HOURS.wireName, cursor.getString(1))
        }
        sqlite.query("SELECT durationSeconds, durationBucketId FROM edition_facets WHERE editionId IN ('e2','e3') ORDER BY editionId").use { cursor ->
            var rows = 0
            while (cursor.moveToNext()) {
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
                rows++
            }
            assertEquals(2, rows)
        }

        // The deterministic backfill can be replayed safely.
        AudiobookDatabase.backfillFacetProjections(sqlite)
        sqlite.query("SELECT COUNT(*) FROM work_genres WHERE workId='w1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        helper.close()
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }

    private fun indexExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", arrayOf(name)).use { it.moveToFirst() }

    private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) found = found || cursor.getString(nameIndex) == column
            found
        }
}
