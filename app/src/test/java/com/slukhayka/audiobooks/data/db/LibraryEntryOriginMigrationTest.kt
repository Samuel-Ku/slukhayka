package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.entries.LibraryEntryOrigin
import com.slukhayka.audiobooks.data.entries.LibraryEntryOriginPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0047 / #867 — migration 46 -> 47 adds the origin fact. Every EXISTING row
 * becomes UNKNOWN (the fact was never recorded), and nothing is declared an
 * auto-seed by guesswork; those rows wait in «Імпортоване».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryEntryOriginMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun openV46(): SupportSQLiteDatabase {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(46) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE library_entries (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, isFavorite INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
                        )
                        db.execSQL("CREATE TABLE libraries_catalogue (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("INSERT INTO library_entries VALUES ('e1','w1',1,1700000000000)")
                        db.execSQL("INSERT INTO library_entries VALUES ('e2','w2',0,1700000001000)")
                        db.execSQL("INSERT INTO libraries_catalogue VALUES ('kept')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        return helper.writableDatabase
    }

    @Test
    fun `every existing entry becomes UNKNOWN and waits for triage`() {
        val db = openV46()
        val before = db.query("SELECT COUNT(*) FROM library_entries").use { it.moveToFirst(); it.getInt(0) }

        AudiobookDatabase.MIGRATION_46_47.migrate(db)

        val origins = mutableListOf<String>()
        db.query("SELECT origin FROM library_entries ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) origins += cursor.getString(0)
        }
        assertEquals(before, origins.size)
        assertTrue(
            "no row is declared an auto-seed by guesswork: $origins",
            origins.all { it == LibraryEntryOrigin.UNKNOWN.name }
        )
        assertTrue(
            "and each one is therefore visible in «Імпортоване»",
            origins.all { LibraryEntryOriginPolicy.needsTriage(LibraryEntryOrigin.valueOf(it)) }
        )
    }

    @Test
    fun `the migration leaves every other row and table untouched`() {
        val db = openV46()
        db.execSQL("INSERT INTO libraries_catalogue VALUES ('second')")

        AudiobookDatabase.MIGRATION_46_47.migrate(db)

        db.query("SELECT id, workId, isFavorite, createdAt FROM library_entries ORDER BY id").use { cursor ->
            cursor.moveToFirst()
            assertEquals("e1", cursor.getString(0))
            assertEquals("w1", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(1_700_000_000_000L, cursor.getLong(3))
            assertTrue(cursor.moveToNext())
            assertEquals("e2", cursor.getString(0))
            assertEquals("w2", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals(1_700_000_001_000L, cursor.getLong(3))
        }
        val kept = db.query("SELECT COUNT(*) FROM libraries_catalogue").use { it.moveToFirst(); it.getInt(0) }
        assertEquals("no row was deleted or hidden", 2, kept)
    }

    private companion object {
        const val DB_NAME = "library-entry-origin-46-47.db"
    }
}
