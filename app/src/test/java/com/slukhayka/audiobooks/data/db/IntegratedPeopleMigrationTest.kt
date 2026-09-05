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

/** Real exported schemas from both lanes must upgrade into one validated database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class IntegratedPeopleMigrationTest {
    @Test fun release26PreservesPersonBookmark() {
        val relative = "schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/26.json"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        verifyUpgrade(JSONObject(file.readText()).getJSONObject("database"), 0)
    }

    @Test fun people25PreservesExistingNotificationCount() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("migrations/people-branch-25.json"))
            .bufferedReader().use { it.readText() }
        verifyUpgrade(JSONObject(text).getJSONObject("database"), 7)
    }

    private fun verifyUpgrade(schema: JSONObject, expectedCount: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val version = schema.getInt("version")
        val name = "integrated-people-$version.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO person_bookmarks (kind,id,displayName,normalizedName,createdAt,lastSeenAt,lastNotifiedAt,notifyEnabled,updatedAt" +
                (if (version == 25) ",lastNotifiedCount" else "") + ") VALUES ('AUTHOR','author:test','Автор','автор',11,22,33,1,44" +
                (if (version == 25) ",7" else "") + ")"
        )
        helper.close()
        val migrated = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .addMigrations(AudiobookDatabase.MIGRATION_25_26, AudiobookDatabase.MIGRATION_26_27)
            .allowMainThreadQueries().build()
        try {
            // Opening through Room validates every entity and index, not only the added column.
            migrated.openHelper.writableDatabase.query(
                "SELECT displayName,createdAt,lastSeenAt,lastNotifiedAt,notifyEnabled,updatedAt,lastNotifiedCount FROM person_bookmarks WHERE id='author:test'"
            ).use { row ->
                check(row.moveToFirst())
                assertEquals("Автор", row.getString(0))
                assertEquals(listOf(11L,22L,33L,1L,44L,expectedCount.toLong()), (1..6).map { row.getLong(it) })
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
