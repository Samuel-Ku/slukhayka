package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #823 — migration 47 to 48 (першу чернетку нумерували 44→45, перенумеровано
 * при злитті з v1.8, де 45 зайняла `readthroughs`) creates the FTS4 search
 * index and backfills one folded row per mergeable Work (narrator from
 * editions, else from edition facets). Works without an identity are
 * skipped; replay is idempotent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkSearchMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `migration 47 to 48 creates works_fts and backfills folded rows`() {
        context.deleteDatabase("work-search-migration-47-48.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("work-search-migration-47-48.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(47) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE works (id TEXT NOT NULL PRIMARY KEY, mergeKey TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, seriesTitle TEXT, seriesUrl TEXT, seriesIndex INTEGER, coverImageUrl TEXT, addedAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE editions (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, language TEXT NOT NULL, narrator TEXT NOT NULL, totalChapters INTEGER NOT NULL, totalDurationSeconds INTEGER NOT NULL, addedAt INTEGER NOT NULL DEFAULT 0)")
                        db.execSQL("CREATE TABLE edition_facets (editionId TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, narratorId TEXT, language TEXT, durationSeconds INTEGER, durationBucketId TEXT, chapterCount INTEGER, isAbridged INTEGER, availabilityAvailable INTEGER, availabilityObservedAtMillis INTEGER, availabilityTtlSeconds INTEGER, updatedAt INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO works VALUES ('w1','кобзар|тарас шевченко','Кобзар (вибране)','Тарас Шевченко','Класика',NULL,NULL,NULL,7)")
                        db.execSQL("INSERT INTO works VALUES ('w2','лісова пісня|леся українка','Лісова пісня','Леся Українка',NULL,NULL,NULL,NULL,8)")
                        db.execSQL("INSERT INTO works VALUES ('w3','','Без автора','',NULL,NULL,NULL,NULL,9)")
                        db.execSQL("INSERT INTO editions VALUES ('e1','w1','uk','Іван Начитувач',12,3600,0)")
                        db.execSQL("INSERT INTO edition_facets VALUES ('e2','w2','Ольга Голос',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val sqlite = helper.writableDatabase

        AudiobookDatabase.MIGRATION_47_48.migrate(sqlite)

        assertTrue(tableExists(sqlite, "works_fts"))
        sqlite.query("SELECT COUNT(*) FROM works_fts").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        // Folded on write: parenthetical gone, lowercased.
        sqlite.query("SELECT title, series, narrator FROM works_fts WHERE workId='w1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("кобзар", cursor.getString(0))
            assertEquals("класика", cursor.getString(1))
            assertEquals("іван начитувач", cursor.getString(2))
        }
        // Narrator falls back to the facet when no edition names one.
        sqlite.query("SELECT narrator FROM works_fts WHERE workId='w2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ольга голос", cursor.getString(0))
        }
        // Bare-prefix MATCH answers from the folded rows (a quoted "tok"*
        // matches nothing — verified against SQLite).
        sqlite.query("SELECT workId FROM works_fts WHERE works_fts MATCH ?", arrayOf("кобз*")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("w1", cursor.getString(0))
        }

        // The backfill replays safely: same rows rewritten, no duplicates.
        assertEquals(2, AudiobookDatabase.backfillWorkSearchIndex(sqlite))
        sqlite.query("SELECT COUNT(*) FROM works_fts").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        helper.close()
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
}
