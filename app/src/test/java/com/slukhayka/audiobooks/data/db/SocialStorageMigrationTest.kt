package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.room.Room
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
 * #916 — room v48 -> v49 adds the local social facts and nothing else.
 *
 * The migration is purely additive: the two new tables start empty (no
 * friendship or block was ever recorded before this version, and none is
 * invented — §6.4), while every existing row survives untouched. Replay is safe
 * (`IF NOT EXISTS`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SocialStorageMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `migration 48 to 49 creates the social tables and keeps v48 rows`() {
        val db = openV48()

        AudiobookDatabase.MIGRATION_48_49.migrate(db)

        assertTrue(tableExists(db, "friendship_states"))
        assertTrue(tableExists(db, "social_blocks"))

        val friendshipColumns = columnsOf(db, "friendship_states")
        assertEquals(listOf("pseudonym", "state", "updatedAt"), friendshipColumns.map { it.first })
        assertEquals(listOf("TEXT", "TEXT", "INTEGER"), friendshipColumns.map { it.second })
        assertEquals(listOf("pseudonym"), primaryKeysOf(db, "friendship_states"))

        val blockColumns = columnsOf(db, "social_blocks")
        assertEquals(listOf("pseudonym", "direction", "blockedAt"), blockColumns.map { it.first })
        assertEquals(listOf("TEXT", "TEXT", "INTEGER"), blockColumns.map { it.second })
        // One row per (pseudonym, direction): the two block directions coexist.
        assertEquals(listOf("pseudonym", "direction"), primaryKeysOf(db, "social_blocks"))

        // Both tables start EMPTY: the upgrade invents no acquaintance (§6.4).
        assertEquals(0, countOf(db, "friendship_states"))
        assertEquals(0, countOf(db, "social_blocks"))

        // A pre-existing v48 row survives the migration untouched.
        db.query("SELECT bookId, deletedAt FROM tombstones").use { cursor ->
            cursor.moveToFirst()
            assertEquals("kept", cursor.getString(0))
            assertEquals(42L, cursor.getLong(1))
        }

        // Replay is safe.
        AudiobookDatabase.MIGRATION_48_49.migrate(db)
        assertEquals(0, countOf(db, "friendship_states"))

        db.close()
    }

    @Test
    fun `the database is at version 49 and registers the social tables`() {
        // Room's @Database annotation is CLASS-retained, so the version is read
        // from the opened database itself — the same fact Room writes.
        val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                assertEquals(49, cursor.getInt(0))
            }
            // The two new entities are registered Room tables, not only SQL.
            assertTrue(tableExists(sqlite, "friendship_states"))
            assertTrue(tableExists(sqlite, "social_blocks"))
        } finally {
            db.close()
        }
    }

    private fun openV48(): SupportSQLiteDatabase {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(48) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // A stand-in for the real v48 schema: the migration must
                        // not touch anything it did not create.
                        db.execSQL(
                            "CREATE TABLE tombstones (" +
                                "bookId TEXT NOT NULL, " +
                                "deletedAt INTEGER NOT NULL, " +
                                "PRIMARY KEY(bookId))"
                        )
                        db.execSQL("INSERT INTO tombstones VALUES ('kept', 42)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        return helper.writableDatabase
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<Pair<String, String>> {
        val columns = mutableListOf<Pair<String, String>>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex) to cursor.getString(typeIndex))
            }
        }
        return columns
    }

    private fun primaryKeysOf(db: SupportSQLiteDatabase, table: String): List<String> {
        val keys = mutableListOf<Pair<Int, String>>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                val pk = cursor.getInt(pkIndex)
                if (pk > 0) keys.add(pk to cursor.getString(nameIndex))
            }
        }
        return keys.sortedBy { it.first }.map { it.second }
    }

    private fun countOf(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private companion object {
        const val DB_NAME = "social-storage-48-49.db"
    }
}
