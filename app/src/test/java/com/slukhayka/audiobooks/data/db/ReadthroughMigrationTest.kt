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
 * ADR-0046 / spec-54 T13 (#863) — migration 45 -> 46 is ADDITIVE: it creates the
 * `readthroughs` table and its indices, and it touches NO existing row. This is
 * the conservative half of the ticket; the backfill comes next and may only
 * classify what the existing data proves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadthroughMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun openV45(): SupportSQLiteDatabase {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(45) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // The legacy shape the device actually carries.
                        db.execSQL(
                            "CREATE TABLE library_entries (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, addedAt INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, narrator TEXT NOT NULL)"
                        )
                        db.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)")
                        db.execSQL("INSERT INTO library_entries VALUES ('entry-1','work-1',1700000000000)")
                        db.execSQL("INSERT INTO audiobooks VALUES ('edition-1','Острів Дума','Диктор')")
                        db.execSQL("INSERT INTO sentinel VALUES ('не чіпати')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        return helper.writableDatabase
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.exists(table: String): Boolean =
        query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
            it.moveToFirst()
        }

    @Test
    fun `migration 45 to 46 creates the readthroughs table without touching a single legacy row`() {
        val db = openV45()
        val entriesBefore = db.count("library_entries")
        val booksBefore = db.count("audiobooks")
        val sentinelBefore = db.count("sentinel")

        AudiobookDatabase.MIGRATION_45_46.migrate(db)

        assertTrue("the new table exists", db.exists("readthroughs"))
        assertEquals("an additive migration adds no rows", 0, db.count("readthroughs"))
        assertEquals("legacy entries are untouched", entriesBefore, db.count("library_entries"))
        assertEquals("legacy books are untouched", booksBefore, db.count("audiobooks"))
        assertEquals("nothing was hidden or moved", sentinelBefore, db.count("sentinel"))
        db.query("SELECT value FROM sentinel").use { cursor ->
            cursor.moveToFirst()
            assertEquals("не чіпати", cursor.getString(0))
        }

        // The indices Room expects for the two lookup columns.
        val indices = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='readthroughs'").use {
            while (it.moveToNext()) indices += it.getString(0)
        }
        assertTrue(
            "both lookup indices exist: $indices",
            indices.contains("index_readthroughs_libraryEntryId") &&
                indices.contains("index_readthroughs_workId")
        )
    }

    @Test
    fun `the migration is idempotent - running it again changes nothing`() {
        val db = openV45()

        AudiobookDatabase.MIGRATION_45_46.migrate(db)
        AudiobookDatabase.MIGRATION_45_46.migrate(db)

        assertTrue(db.exists("readthroughs"))
        assertEquals(0, db.count("readthroughs"))
    }

    private companion object {
        const val DB_NAME = "readthrough-migration-45-46.db"
    }
}
