package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecommendationPreferencesRoomTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `migration 20 to 21 adds preferences without touching existing data`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("recommendation-migration-20-21.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)")
                        db.execSQL("INSERT INTO sentinel VALUES ('kept')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val sqlite = helper.writableDatabase

        AudiobookDatabase.MIGRATION_20_21.migrate(sqlite)

        sqlite.query("SELECT value FROM sentinel").use { cursor ->
            cursor.moveToFirst()
            assertEquals("kept", cursor.getString(0))
        }
        sqlite.query("PRAGMA table_info(recommendation_preferences)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            assertEquals(setOf("kind", "targetKey", "sourceWorkId", "createdAt"), columns)
        }
        helper.close()
    }

    @Test
    fun `explicit feedback is idempotent reversible and reset is scoped`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.audiobookDao()
        val first = RecommendationPreferenceEntity(
            kind = RecommendationPreferenceEntity.HIDE_WORK,
            targetKey = "work",
            sourceWorkId = "work",
            createdAt = 1
        )

        dao.upsertRecommendationPreference(first)
        dao.upsertRecommendationPreference(first.copy(createdAt = 2))
        assertEquals(listOf(2L), dao.observeRecommendationPreferences().first().map { it.createdAt })

        dao.deleteRecommendationPreference(first.kind, first.targetKey)
        assertTrue(dao.observeRecommendationPreferences().first().isEmpty())

        dao.upsertRecommendationPreference(first)
        dao.clearRecommendationPreferences()
        assertTrue(dao.observeRecommendationPreferences().first().isEmpty())
        db.close()
    }
}
