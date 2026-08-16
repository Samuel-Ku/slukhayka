package com.example.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec-26 T7 (#181) — the v18 → v19 migration (the series' P577 publication
 * year, the age signal of the tiered refresh rule): pure addition, so
 * pre-existing series rows survive with a NULL year, which the tier treats
 * as unknown-age. Mirrors the MIGRATION_17_18 convention in
 * DeepModulesRoomTest (a real v18 database upgraded by the migration
 * object).
 */
@RunWith(AndroidJUnit4::class)
class UniverseMigrationsTest {

    @Test
    fun `migrate 18 to 19 adds publicationYear and keeps existing rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-18-test.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(18) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v18 schema: the series table without the year.
                    db.execSQL(
                        "CREATE TABLE series (id TEXT NOT NULL, title TEXT NOT NULL, " +
                            "url TEXT, universeId TEXT, positionInUniverse INTEGER, " +
                            "PRIMARY KEY(id))"
                    )
                    db.execSQL(
                        "INSERT INTO series (id, title, url, universeId, positionInUniverse) " +
                            "VALUES ('first-law:2', 'Епоха божевілля', 'https://4read.org/xfsearch/cikl/epoha-bozhevillja/', 'first-law', 2)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        AudiobookDatabase.MIGRATION_18_19.migrate(db)

        // The year column exists and the pre-existing row survived with a
        // NULL year (unknown age → the tier never treats it as hot).
        assertTrue(
            "publicationYear column must exist",
            tableColumns(db, "series").contains("publicationYear")
        )
        db.query("SELECT id, title, positionInUniverse, publicationYear FROM series").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("first-law:2", cursor.getString(0))
            assertEquals("Епоха божевілля", cursor.getString(1))
            assertEquals(2, cursor.getInt(2))
            assertTrue(cursor.isNull(3))
        }
        db.close()
    }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(1)
        }
        return columns
    }
}
