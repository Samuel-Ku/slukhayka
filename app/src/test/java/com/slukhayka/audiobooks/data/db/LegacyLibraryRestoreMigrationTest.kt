package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1465 — the P0 that reached the owner: a library saved before `origin` existed came back
 * as ONE visible book out of 211.
 *
 * Measured on the owner's phone (schema 26, 211 `library_entries`, 1 favourite, 0 listening
 * progress): `MIGRATION_54_55` promotes only rows with listening evidence, so 210 rows keep
 * `origin = 'UNKNOWN'` and the library query — which filters on
 * `origin IN ('EXPLICIT_SAVE','EXPLICIT_IMPORT')` — hides them forever.
 *
 * The fix is not "promote more rows by a better guess". At schema 46 a `library_entries` row
 * could only be created by an explicit user action, so `UNKNOWN` there is not an unknown
 * origin: it is "the column did not exist yet". This test pins the invariant the owner
 * actually needs — **every book that was in the library before the migration is in the
 * library after it** — and it fails on `main` (1 of 211) until the migration is corrected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyLibraryRestoreMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The owner's real shape, not a convenient one: many books, almost no listening
     * evidence. A fixture with one favourite and one listened book would pass on `main`,
     * which is exactly how the regression shipped.
     */
    private val seededBooks = 211

    private fun schema(fileName: String): JSONObject {
        val relative = "schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/$fileName"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        return JSONObject(file.readText()).getJSONObject("database")
    }

    /** Opens a real schema-46 database from the checked-in JSON schema, then seeds it. */
    private fun openV46(name: String): SupportSQLiteOpenHelper {
        val schema46 = schema("46.json")
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(46) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema46.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) {
                                db.execSQL(
                                    indices.getJSONObject(j).getString("createSql")
                                        .replace("\${TABLE_NAME}", table)
                                )
                            }
                        }
                        val setup = schema46.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))

                        // The owner's library as it was at schema 26/46: many rows, no favourite
                        // except one, no playback progress at all.
                        for (i in 0 until seededBooks) {
                            db.execSQL(
                                "INSERT INTO library_entries (id, workId, isFavorite, createdAt) " +
                                    "VALUES ('e$i', 'w$i', ${if (i == 0) 1 else 0}, ${1700000000000L + i})"
                            )
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        ).also { it.writableDatabase }
    }

    private fun rawDb(name: String, version: Int): SupportSQLiteDatabase =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        ).writableDatabase

    private val migrations46To55 = arrayOf(
        AudiobookDatabase.MIGRATION_46_47,
        AudiobookDatabase.MIGRATION_47_48,
        AudiobookDatabase.MIGRATION_48_49,
        AudiobookDatabase.MIGRATION_49_50,
        AudiobookDatabase.MIGRATION_50_51,
        AudiobookDatabase.MIGRATION_51_52,
        AudiobookDatabase.MIGRATION_52_53,
        AudiobookDatabase.MIGRATION_53_54,
        AudiobookDatabase.MIGRATION_54_55,
    )

    /** Counts exactly what the library screen counts — the same predicate the DAO uses. */
    private fun visibleToLibrary(db: SupportSQLiteDatabase): Int =
        db.query(
            "SELECT COUNT(*) FROM library_entries " +
                "WHERE origin IN ('EXPLICIT_SAVE','EXPLICIT_IMPORT')"
        ).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun `every book saved before origin existed is visible in the library after migration`() {
        val name = "legacy-restore.db"
        context.deleteDatabase(name)

        val helper = openV46(name)
        try {
            helper.writableDatabase.close()
        } catch (_: Exception) {
            // The helper holds the only handle; closing it is best-effort.
        }

        val db = rawDb(name, 46)
        try {
            val before = db.query("SELECT COUNT(*) FROM library_entries")
                .use { it.moveToFirst(); it.getInt(0) }
            assertEquals("fixture must reproduce the owner's library", seededBooks, before)

            for (migration in migrations46To55) migration.migrate(db)
            db.version = 55

            assertEquals(
                "every book the owner had must survive the migration into the library",
                seededBooks,
                visibleToLibrary(db),
            )
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `a book with listening evidence is still visible`() {
        val name = "legacy-restore-favourite.db"
        context.deleteDatabase(name)

        val helper = openV46(name)
        try {
            helper.writableDatabase.close()
        } catch (_: Exception) {
        }

        val db = rawDb(name, 46)
        try {
            for (migration in migrations46To55) migration.migrate(db)
            db.version = 55

            db.query("SELECT COUNT(*) FROM library_entries WHERE isFavorite = 1")
                .use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }

            assertEquals(seededBooks, visibleToLibrary(db))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
