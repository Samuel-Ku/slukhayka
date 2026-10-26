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
 * #1413-регресія — міграція 54→55 повертає користувачу дані, які v1.6 сховав
 * фільтром `origin IN ('EXPLICIT_SAVE','EXPLICIT_IMPORT')`.
 *
 * ПРАВИЛО (ADR-0047 §2): особистим вважається рядок, чий початок був ЯВНОЮ дією
 * слухача — збереження, свідомий імпорт **або фактично розпочате
 * прослуховування**. Тому 54→55 підвищує до `EXPLICIT_SAVE` рядки з доказом:
 * `isFavorite = 1` або наявний прогрес прослуховування.
 *
 * Цей тест пінить ОБИДВА боки правила — і підвищення, і його межі:
 * рядок без жодного доказу МУСИТЬ лишитись `UNKNOWN`, інакше міграція
 * вигадувала б дані, яких не було.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OriginBackfillMigrationTest {

    @Test
    fun `v54 promotes only rows with evidence of a deliberate start`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "origin-backfill-migration-54.db"
        context.deleteDatabase(name)
        val relative = "schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/54.json"
        val schema = JSONObject(
            listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
        ).getJSONObject("database")

        val legacy = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(54) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(
                                entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table)
                            )
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) {
                                // SQL індексів у схемі ТЕЖ містить плейсхолдер
                                // ${'$'}{TABLE_NAME} — без заміни SQLite падає з
                                // "no such table: main.${'$'}{TABLE_NAME}".
                                db.execSQL(
                                    indices.getJSONObject(j).getString("createSql")
                                        .replace("${'$'}{TABLE_NAME}", table)
                                )
                            }
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))

                        fun book(id: String) = db.execSQL(
                            "INSERT INTO audiobooks (id,title,author,narrator,description," +
                                "coverDrawableRes,genre,sourceUrl,isDownloaded," +
                                "totalDurationSeconds,totalChapters,rating) " +
                                "VALUES ('$id','t','a','n','d',0,'g','s',0,0,0,0.0)"
                        )

                        // 1) Вподобана книга — свідоме збереження.
                        book("saved")
                        db.execSQL(
                            "INSERT INTO library_entries (id,workId,origin,isFavorite," +
                                "downloadProgress) VALUES ('saved','w-saved','UNKNOWN',1,0.0)"
                        )

                        // 2) Книга з реальним прогресом — прослуховування почалось.
                        book("played")
                        db.execSQL(
                            "INSERT INTO library_entries (id,workId,origin,isFavorite," +
                                "downloadProgress) VALUES ('played','w-played','UNKNOWN',0,0.0)"
                        )
                        db.execSQL(
                            "INSERT INTO editions (id,workId,language,narrator," +
                                "totalChapters,totalDurationSeconds) " +
                                "VALUES ('ed-played','played','uk','n',1,100)"
                        )
                        db.execSQL(
                            "INSERT INTO playback_progress (editionId,bookId," +
                                "currentChapterIndex,currentPositionSeconds,lastListenedAt," +
                                "isCompleted) VALUES ('ed-played','played',0,37,1700000000,0)"
                        )

                        // 3) Без жодного доказу — МУСИТЬ лишитись UNKNOWN.
                        book("silent")
                        db.execSQL(
                            "INSERT INTO library_entries (id,workId,origin,isFavorite," +
                                "downloadProgress) VALUES ('silent','w-silent','UNKNOWN',0,0.0)"
                        )

                        // 4) Уже особистий — не переписуємо.
                        book("imported")
                        db.execSQL(
                            "INSERT INTO library_entries (id,workId,origin,isFavorite," +
                                "downloadProgress) VALUES ('imported','w-imported','EXPLICIT_IMPORT',0,0.0)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build()
        )
        legacy.writableDatabase
        legacy.close()

        val migrated = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .addMigrations(AudiobookDatabase.MIGRATION_54_55, AudiobookDatabase.MIGRATION_55_56)
            .allowMainThreadQueries().build()
        try {
            val db = migrated.openHelper.writableDatabase
            fun origin(id: String): String =
                db.query("SELECT origin FROM library_entries WHERE id='$id'").use {
                    check(it.moveToFirst())
                    it.getString(0)
                }

            assertEquals("вподобана книга мусить стати особистою", "EXPLICIT_SAVE", origin("saved"))
            assertEquals("книга з прогресом мусить стати особистою", "EXPLICIT_SAVE", origin("played"))
            assertEquals(
                "рядок БЕЗ доказу мусить лишитись UNKNOWN — міграція не вигадує даних",
                "UNKNOWN",
                origin("silent"),
            )
            assertEquals(
                "уже особистий рядок не переписується",
                "EXPLICIT_IMPORT",
                origin("imported"),
            )
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
