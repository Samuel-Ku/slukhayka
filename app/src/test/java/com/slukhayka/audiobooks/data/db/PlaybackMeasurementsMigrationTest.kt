package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1173 (T9) — the 53→54 measurement layer on a real exported schema.
 *
 * The migration only ADDS: three columns and two tables. Nothing existing may
 * move, and the new numbers must start at zero — the past was never observed
 * with a source attached, so any non-zero value here would be invented
 * (ADR-0014).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaybackMeasurementsMigrationTest {
    @Test fun `v53 keeps every existing measurement and starts the new ones empty`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "playback-measurements-migration-53.db"
        context.deleteDatabase(name)
        openV53(context, name).also { db ->
            db.execSQL(
                "INSERT INTO audiobooks (id,title,author,narrator,description,coverDrawableRes,coverImageUrl," +
                    "genre,sourceUrl,isDownloaded,totalDurationSeconds,totalChapters,rating) " +
                    "VALUES ('book','Книга','Автор','','',0,NULL,'','',0,0,0,0)"
            )
            db.execSQL(
                "INSERT INTO library_entries (id,workId,origin,isFavorite,createdAt,downloadProgress) " +
                    "VALUES ('book','work','UNKNOWN',1,123,0.5)"
            )
            db.execSQL(
                "INSERT INTO listening_stats (dateIso,listenedSeconds,verifiedListenedMillis) " +
                    "VALUES ('2026-10-01',1800,60000)"
            )
            db.execSQL("INSERT INTO achievements (id,earnedAt) VALUES ('first_book',111)")
            db.execSQL("INSERT INTO achievement_facts (`key`,observedAt) VALUES ('PLAYBACK_STARTED',222)")
        }.close()

        val migrated = openCurrent(context, name)
        try {
            val db = migrated.openHelper.writableDatabase
            db.query(
                "SELECT listenedSeconds,verifiedListenedMillis,offlineListenedMillis,castListenedMillis," +
                    "nightListenedMillis FROM listening_stats WHERE dateIso='2026-10-01'"
            ).use { row ->
                check(row.moveToFirst())
                assertEquals("записаний час не рухається", 1800L, row.getLong(0))
                assertEquals("verified-час не рухається", 60000L, row.getLong(1))
                assertEquals("офлайн стартує з нуля", 0L, row.getLong(2))
                assertEquals("каст стартує з нуля", 0L, row.getLong(3))
                assertEquals("ніч стартує з нуля", 0L, row.getLong(4))
            }
            db.query("SELECT origin FROM library_entries WHERE id='book'").use {
                check(it.moveToFirst())
                assertEquals("UNKNOWN", it.getString(0))
            }
            db.query("SELECT earnedAt FROM achievements WHERE id='first_book'").use {
                check(it.moveToFirst())
                assertEquals(111L, it.getLong(0))
            }
            for (table in listOf("playback_sessions", "achievement_counters")) {
                db.query("SELECT COUNT(*) FROM $table").use {
                    check(it.moveToFirst())
                    assertEquals("$table мусить бути порожньою на старій базі", 0L, it.getLong(0))
                }
            }
            db.query("PRAGMA user_version").use {
                check(it.moveToFirst())
                assertEquals("53→54 виконалась рівно раз", 54, it.getInt(0))
            }
        } finally {
            migrated.close()
        }

        // Reopen: Room runs 53→54 exactly once (user_version is 54), so the
        // second open is a no-op and every number must be exactly as it was.
        // DDL replay is not idempotent by nature — this is the honest form of
        // "running it again changes nothing" for a schema migration.
        val reopened = openCurrent(context, name)
        try {
            val db = reopened.openHelper.writableDatabase
            db.query("SELECT verifiedListenedMillis FROM listening_stats WHERE dateIso='2026-10-01'").use {
                check(it.moveToFirst())
                assertEquals(60000L, it.getLong(0))
            }
            db.query("PRAGMA table_info(listening_stats)").use { columns ->
                val names = buildList { while (columns.moveToNext()) add(columns.getString(1)) }
                assertEquals(
                    listOf(
                        "dateIso", "listenedSeconds", "verifiedListenedMillis",
                        "offlineListenedMillis", "castListenedMillis", "nightListenedMillis"
                    ),
                    names
                )
            }
            // The new tables really are usable after the migration.
            db.execSQL(
                "INSERT INTO playback_sessions (startedAt,endedAt,verifiedMillis,offlineMillis,castMillis) " +
                    "VALUES (1,2,3,4,5)"
            )
            db.execSQL("INSERT INTO achievement_counters (`key`,count) VALUES ('end_of_chapter_arm',1)")
        } finally {
            reopened.close()
        }
        val afterInsert = openCurrent(context, name)
        try {
            val db = afterInsert.openHelper.writableDatabase
            db.query("SELECT startedAt,endedAt,verifiedMillis,offlineMillis,castMillis FROM playback_sessions").use {
                check(it.moveToFirst())
                assertEquals(listOf(1L, 2L, 3L, 4L, 5L), (0..4).map { column -> it.getLong(column) })
            }
            db.query("SELECT count FROM achievement_counters WHERE `key`='end_of_chapter_arm'").use {
                check(it.moveToFirst())
                assertEquals(1L, it.getLong(0))
            }
        } finally {
            afterInsert.close()
            context.deleteDatabase(name)
        }
    }

    /** A real v53 database built from the exported schema, with its own data. */
    private fun openV53(context: Context, name: String): SupportSQLiteDatabase {
        val relative = "schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/53.json"
        val schema = JSONObject(listOf(File(relative), File("app/$relative")).first { it.isFile }.readText())
            .getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(53) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) {
                                db.execSQL(
                                    indices.getJSONObject(j).getString("createSql")
                                        .replace("${'$'}{TABLE_NAME}", table)
                                )
                            }
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        return helper.writableDatabase
    }

    private fun openCurrent(context: Context, name: String) = Room.databaseBuilder(
        context, AudiobookDatabase::class.java, name
    )
        .addMigrations(AudiobookDatabase.MIGRATION_53_54)
        .allowMainThreadQueries()
        .build()
}
