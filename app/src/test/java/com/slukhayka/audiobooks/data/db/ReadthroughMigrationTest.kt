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
                            "CREATE TABLE library_entries (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE audiobooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, narrator TEXT NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE playback_progress (editionId TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, currentPositionSeconds INTEGER NOT NULL, lastListenedAt INTEGER NOT NULL, isCompleted INTEGER NOT NULL)"
                        )
                        db.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)")
                        // Three shapes of evidence: a FINISHED pass, a started
                        // one, and a library row with no playback at all.
                        db.execSQL("INSERT INTO library_entries VALUES ('entry-done','work-1',1700000000000)")
                        db.execSQL("INSERT INTO library_entries VALUES ('entry-started','work-1',1700000001000)")
                        db.execSQL("INSERT INTO library_entries VALUES ('entry-clean','work-2',1700000002000)")
                        db.execSQL("INSERT INTO audiobooks VALUES ('entry-done','Острів Дума','Диктор')")
                        db.execSQL("INSERT INTO playback_progress VALUES ('entry-done','entry-done',3600,1700000500000,1)")
                        db.execSQL("INSERT INTO playback_progress VALUES ('entry-started','entry-started',120,1700000600000,0)")
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
        assertEquals(
            "every existing library row becomes exactly one audio pass",
            entriesBefore,
            db.count("readthroughs")
        )
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
        assertEquals("a second run adds no duplicate pass", 3, db.count("readthroughs"))
    }

    @Test
    fun `the backfill classifies ONLY what the evidence proves`() {
        val db = openV45()

        AudiobookDatabase.MIGRATION_45_46.migrate(db)

        db.query(
            "SELECT id, state, finishedAt, editionId, unit, unitValue, journalJson " +
                "FROM readthroughs ORDER BY id"
        ).use { cursor ->
            val rows = mutableListOf<List<Any?>>()
            while (cursor.moveToNext()) {
                rows += listOf(
                    cursor.getString(0), cursor.getString(1), cursor.isNull(2), cursor.getLong(5),
                    cursor.getString(3), cursor.getString(4), cursor.getString(6)
                )
            }
            assertEquals(3, rows.size)

            val done = rows.first { it[0] == "rt-audio-entry-done" }
            assertEquals("a completed playback PROVES finished", "FINISHED", done[1])
            assertEquals(
                "the live position is NOT copied: Listening State stays its one truth",
                0L,
                done[3]
            )
            assertEquals("the audio Edition is named", "entry-done", done[4])
            assertEquals("SECONDS", done[5])
            assertEquals("the old data never journaled; nothing is invented", "[]", done[6])

            val started = rows.first { it[0] == "rt-audio-entry-started" }
            assertEquals("a progress row proves in progress", "IN_PROGRESS", started[1])
            assertEquals(
                "and the seconds still live in Listening State, not here",
                0L,
                started[3]
            )

            val clean = rows.first { it[0] == "rt-audio-entry-clean" }
            assertEquals("no evidence means PLANNED, never invented progress", "PLANNED", clean[1])
            assertEquals(0L, clean[3])
        }
    }

    private companion object {
        const val DB_NAME = "readthrough-migration-45-46.db"
    }
}
