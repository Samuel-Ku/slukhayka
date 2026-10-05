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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AchievementMigrationTest {
    @Test fun `v50 preserves listening and library data without inventing award history`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "achievement-migration-50.db"
        context.deleteDatabase(name)
        val relative = "schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/50.json"
        val schema = JSONObject(listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()).getJSONObject("database")
        val legacy = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(50) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                        db.execSQL("INSERT INTO audiobooks (id,title,author,narrator,description,coverDrawableRes,coverImageUrl,genre,sourceUrl,isDownloaded,totalDurationSeconds,totalChapters,rating) VALUES ('book','Книга','Автор','','',0,NULL,'','',0,0,0,0)")
                        db.execSQL("INSERT INTO library_entries (id,workId,origin,isFavorite,createdAt,downloadProgress) VALUES ('book','work','UNKNOWN',1,123,0.5)")
                        db.execSQL("INSERT INTO bookmarks (bookId,editionId,chapterIndex,chapterTitle,timestampSeconds,note,createdAt) VALUES ('book','edition',2,'Розділ',37,'Нотатка',456)")
                        db.execSQL("INSERT INTO playback_progress (editionId,bookId,currentChapterIndex,currentPositionSeconds,lastListenedAt,isCompleted,preferredSpeed) VALUES ('edition','book',2,37,789,1,1.5)")
                        db.execSQL("INSERT INTO tombstones (bookId,deletedAt) VALUES ('removed-book',321)")
                        db.execSQL("INSERT INTO listening_stats (dateIso,listenedSeconds) VALUES ('2026-10-01',18000000)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        legacy.writableDatabase
        legacy.close()
        val migrated = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .addMigrations(AudiobookDatabase.MIGRATION_50_51).allowMainThreadQueries().build()
        try {
            val db = migrated.openHelper.writableDatabase
            db.query("SELECT listenedSeconds FROM listening_stats WHERE dateIso='2026-10-01'").use {
                check(it.moveToFirst()); assertEquals(18000000L, it.getLong(0))
            }
            db.query("SELECT verifiedListenedMillis FROM listening_stats").use { check(it.moveToFirst()); assertEquals(0L,it.getLong(0)) }
            db.query("SELECT origin,isFavorite,createdAt,downloadProgress FROM library_entries WHERE id='book'").use {
                check(it.moveToFirst()); assertEquals("UNKNOWN",it.getString(0)); assertEquals(1,it.getInt(1)); assertEquals(123L,it.getLong(2)); assertEquals(0.5,it.getDouble(3),0.0)
            }
            db.query("SELECT note,timestampSeconds FROM bookmarks WHERE bookId='book'").use {
                check(it.moveToFirst()); assertEquals("Нотатка",it.getString(0)); assertEquals(37L,it.getLong(1))
            }
            db.query("SELECT currentChapterIndex,currentPositionSeconds,isCompleted,preferredSpeed FROM playback_progress WHERE bookId='book'").use {
                check(it.moveToFirst()); assertEquals(2,it.getInt(0)); assertEquals(37L,it.getLong(1)); assertEquals(1,it.getInt(2)); assertEquals(1.5,it.getDouble(3),0.0)
            }
            db.query("SELECT deletedAt FROM tombstones WHERE bookId='removed-book'").use { check(it.moveToFirst()); assertEquals(321L,it.getLong(0)) }
            for (table in listOf("achievements", "achievement_facts")) db.query("SELECT COUNT(*) FROM $table").use {
                check(it.moveToFirst()); assertEquals(0L, it.getLong(0))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
