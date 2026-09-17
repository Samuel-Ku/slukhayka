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

    @Test fun people27PreservesNotificationCount() {
        val relative = "schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/27.json"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        verifyUpgrade(JSONObject(file.readText()).getJSONObject("database"), 7)
    }

    @Test fun popularity27PreservesSourceSignals() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("migrations/popularity-branch-27.json"))
            .bufferedReader().use { it.readText() }
        verifyUpgrade(JSONObject(text).getJSONObject("database"), 0)
    }

    private fun verifyUpgrade(schema: JSONObject, expectedCount: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val version = schema.getInt("version")
        val hasCount = schema.toString().contains("lastNotifiedCount")
        val hasSignals = schema.toString().contains("popularity_assertions")
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
                (if (hasCount) ",lastNotifiedCount" else "") + ") VALUES ('AUTHOR','author:test','Автор','автор',11,22,33,1,44" +
                (if (hasCount) ",7" else "") + ")"
        )
        if (hasSignals) helper.writableDatabase.execSQL(
            "INSERT INTO popularity_assertions VALUES ('signal:test','rank','книга|автор','12','4read',123)"
        )
        helper.close()
        val migrated = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .addMigrations(
                AudiobookDatabase.MIGRATION_25_26, AudiobookDatabase.MIGRATION_26_27,
                AudiobookDatabase.MIGRATION_27_28, AudiobookDatabase.MIGRATION_28_29,
                AudiobookDatabase.MIGRATION_29_30,
                AudiobookDatabase.MIGRATION_30_31,
                AudiobookDatabase.MIGRATION_31_32,
                // #812 — ланцюг мусить доходити до поточної версії, інакше
                // Room не знаходить шляху (25→39) і тест падає.
                AudiobookDatabase.MIGRATION_32_33, AudiobookDatabase.MIGRATION_33_34,
                AudiobookDatabase.MIGRATION_34_35, AudiobookDatabase.MIGRATION_35_36,
                AudiobookDatabase.MIGRATION_36_37, AudiobookDatabase.MIGRATION_37_38,
                AudiobookDatabase.MIGRATION_38_39,
                // #812 — ланцюг мусить доходити до ПОТОЧНОЇ версії. Кожна
                // нова міграція вимагає дописати себе сюди, інакше Room не
                // знаходить шляху 25→N і тест падає.
                AudiobookDatabase.MIGRATION_39_40,
                AudiobookDatabase.MIGRATION_40_41,
                AudiobookDatabase.MIGRATION_41_42,
                AudiobookDatabase.MIGRATION_42_43,
                AudiobookDatabase.MIGRATION_43_44,
                AudiobookDatabase.MIGRATION_44_45,
                // #883 — the database is at 47 now: a path from an OLD schema
                // must reach the CURRENT version, so the two v1.8 steps that
                // added `readthroughs` and `library_entries.origin` belong here
                // too. Without them Room refuses the whole path ("A migration
                // from 26 to 47 was required but not found").
                AudiobookDatabase.MIGRATION_45_46,
                AudiobookDatabase.MIGRATION_46_47,
                AudiobookDatabase.MIGRATION_47_48
            )
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
            if (hasSignals) migrated.openHelper.writableDatabase.query(
                "SELECT rawValue,observedAt FROM popularity_assertions WHERE id='signal:test'"
            ).use { row ->
                check(row.moveToFirst())
                assertEquals("12", row.getString(0))
                assertEquals(123L, row.getLong(1))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
