package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-40 #281 — локальний м'ют (`hidden_reviewers`, schema v20): the
 * migration 19→20 and the mute lifecycle (hide → listed, unhide → gone,
 * re-hide idempotent, survives reopen). Mirrors the MIGRATION_18_19
 * convention of [UniverseMigrationsTest] / DeepModulesRoomTest: a real v19
 * database upgraded by the migration object, plus an in-memory/file-backed
 * Room over the live DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HiddenReviewersRoomTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ---------------------------------------------------------------------
    // Migration 19 -> 20 (pure table addition)
    // ---------------------------------------------------------------------

    @Test
    fun `migrate 19 to 20 creates hidden_reviewers and keeps existing rows`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-19-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v19 schema: a series row the migration must preserve.
                    db.execSQL(
                        "CREATE TABLE series (id TEXT NOT NULL, title TEXT NOT NULL, " +
                            "url TEXT, universeId TEXT, positionInUniverse INTEGER, " +
                            "publicationYear INTEGER, PRIMARY KEY(id))"
                    )
                    db.execSQL(
                        "INSERT INTO series (id, title, url, universeId, positionInUniverse) " +
                            "VALUES ('first-law:2', 'Епоха божевілля', NULL, 'first-law', 2)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val db = factory.create(config).writableDatabase

        AudiobookDatabase.MIGRATION_19_20.migrate(db)

        assertTrue("hidden_reviewers table must exist", tableExists(db, "hidden_reviewers"))
        assertEquals(setOf("authorName", "hiddenAt"), tableColumns(db, "hidden_reviewers").toSet())
        // The new table accepts rows and enforces its single-column PK.
        db.execSQL("INSERT INTO hidden_reviewers (authorName, hiddenAt) VALUES ('Спамер', 1700000000000)")
        db.execSQL("INSERT OR REPLACE INTO hidden_reviewers (authorName, hiddenAt) VALUES ('Спамер', 1700000001000)")
        db.query("SELECT COUNT(*), MAX(hiddenAt) FROM hidden_reviewers").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            assertEquals(1700000001000L, cursor.getLong(1))
        }
        // Every v19 row survived untouched.
        db.query("SELECT title, positionInUniverse FROM series").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Епоха божевілля", cursor.getString(0))
            assertEquals(2, cursor.getInt(1))
        }
        db.close()
    }

    // ---------------------------------------------------------------------
    // The mute lifecycle over the live DAO
    // ---------------------------------------------------------------------

    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    private fun openInMemory(): AudiobookDatabase =
        Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `hidden authors are listed alphabetically`() = runBlocking {
        db = openInMemory()
        dao = db.audiobookDao()

        dao.hideAuthor(HiddenReviewerEntity("Марія", 1000))
        dao.hideAuthor(HiddenReviewerEntity("Антон", 2000))
        dao.hideAuthor(HiddenReviewerEntity("Богдан", 3000))

        val listed = dao.hiddenAuthors()
        assertEquals(listOf("Антон", "Богдан", "Марія"), listed.map { it.authorName })
    }

    @Test
    fun `unhide removes the author from the list`() = runBlocking {
        db = openInMemory()
        dao = db.audiobookDao()

        dao.hideAuthor(HiddenReviewerEntity("Спамер", 1000))
        dao.unhideAuthor("Спамер")

        assertTrue(dao.hiddenAuthors().isEmpty())
    }

    @Test
    fun `re-hiding is idempotent - one row per author with a refreshed stamp`() = runBlocking {
        db = openInMemory()
        dao = db.audiobookDao()

        dao.hideAuthor(HiddenReviewerEntity("Автор", 1000))
        dao.hideAuthor(HiddenReviewerEntity("Автор", 2000))

        val listed = dao.hiddenAuthors()
        assertEquals(1, listed.size)
        assertEquals("Автор", listed.single().authorName)
        assertEquals(2000L, listed.single().hiddenAt)
    }

    @Test
    fun `the mute list survives reopening the database`() = runBlocking {
        // A real (file-backed) database: close and reopen the same file —
        // the rows must come back exactly as hidden.
        val name = "mute-reopen-test.db"
        val first = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        first.audiobookDao().hideAuthor(HiddenReviewerEntity("Тихий автор", 1234567890))
        first.close()

        val reopened = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        val listed = reopened.audiobookDao().hiddenAuthors()
        reopened.close()

        assertEquals(listOf("Тихий автор"), listed.map { it.authorName })
        assertEquals(listOf(1234567890L), listed.map { it.hiddenAt })
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { cursor ->
            cursor.moveToFirst()
        }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(1)
        }
        return columns
    }
}
