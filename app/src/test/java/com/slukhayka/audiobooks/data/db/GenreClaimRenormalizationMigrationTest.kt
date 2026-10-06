package com.slukhayka.audiobooks.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.achievements.AchievementEvaluator
import com.slukhayka.audiobooks.data.achievements.RoomAchievementProgressSource
import com.slukhayka.audiobooks.data.achievements.RoomAchievementStore
import com.slukhayka.audiobooks.data.facets.FacetIdentity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #702 (T4, зріз 2) — жанровий словник вивчив полиці, які джерела вже
 * заявляють, тож рядок, записаний ДО цього, мусить переїхати під канонічний
 * id. Інакше бібліотека з реальними заявами «жахи» показувала б нуль поступу
 * до нагороди «10 книг у жанрі», хоча всі заяви існують.
 *
 * Перевіряється на СПРАВЖНІЙ схемі v52 і справжній міграції: рядок несе хеш
 * власного написання (як його писав старий шлях запису), а єдине, що він зберіг
 * дослівно, — сама заява.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GenreClaimRenormalizationMigrationTest {

    @Test
    fun `a claim stored before the dictionary reaches the named award`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "genre-renormalization-52.db"
        context.deleteDatabase(name)
        val horrorBefore = FacetIdentity.boundedId("genre", "жахи")
        val mysticaBefore = FacetIdentity.boundedId("genre", "містика")
        val schema = schema("52.json")

        val legacy = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(52) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createFromSchema(db, schema)
                        db.execSQL("INSERT INTO genre_facets (id, displayName, normalizedName) VALUES ('$horrorBefore','Жахи','жахи')")
                        db.execSQL("INSERT INTO genre_facets (id, displayName, normalizedName) VALUES ('$mysticaBefore','Містика','містика')")
                        for (index in 1..11) {
                            val work = "w$index"
                            db.execSQL("INSERT INTO library_entries (id, workId, origin, isFavorite, createdAt, downloadProgress) VALUES ('$work','$work','EXPLICIT_SAVE',0,1,0)")
                            db.execSQL("INSERT INTO work_genres (workId, genreId, sourceId) VALUES ('$work','$horrorBefore','4read')")
                            db.execSQL("INSERT INTO genre_assertions (id, assertionId, workId, genreId, rawText, sourceId, observedAt) VALUES ('a$work','a$work','$work','$horrorBefore','жахи','4read',1)")
                        }
                        // Той самий твір із ДРУГОГО джерела: та сама заява — та сама книга.
                        db.execSQL("INSERT INTO work_genres (workId, genreId, sourceId) VALUES ('w11','$horrorBefore','sound-books')")
                        db.execSQL("INSERT INTO genre_assertions (id, assertionId, workId, genreId, rawText, sourceId, observedAt) VALUES ('a-w11-sb','a-w11-sb','w11','$horrorBefore','Жахи','sound-books',2)")
                        // Жанр, якого словник не знає, лишається як був.
                        db.execSQL("INSERT INTO work_genres (workId, genreId, sourceId) VALUES ('w1','$mysticaBefore','4read')")
                        db.execSQL("INSERT INTO genre_assertions (id, assertionId, workId, genreId, rawText, sourceId, observedAt) VALUES ('a-mystica','a-mystica','w1','$mysticaBefore','Містика','4read',1)")
                        // Уже канонічний жанр не рухається.
                        db.execSQL("INSERT INTO work_genres (workId, genreId, sourceId) VALUES ('w2','fantasy','4read')")
                        db.execSQL("INSERT INTO genre_assertions (id, assertionId, workId, genreId, rawText, sourceId, observedAt) VALUES ('a-fantasy','a-fantasy','w2','fantasy','Фентезі','4read',1)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        legacy.writableDatabase
        legacy.close()

        val migrated = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .addMigrations(AudiobookDatabase.MIGRATION_52_53)
            .allowMainThreadQueries()
            .build()
        try {
            val db = migrated.openHelper.writableDatabase
            val achievementDao = migrated.achievementDao()

            assertEquals(
                "канонічний підпис полиці мусить з'явитися",
                "Жахи",
                db.query("SELECT displayName FROM genre_facets WHERE id='horror'").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                }
            )
            val counts = achievementDao.observeGenreBookCounts().first().associate { it.genreId to it.works }
            assertEquals("11 заявлених творів — це 11 книг", 11L, counts["horror"])
            assertTrue(
                "два джерела одного твору не мають давати 12: $counts",
                counts["horror"] != 12L
            )
            assertTrue("хеша «жахи» більше не має бути: $counts", horrorBefore !in counts)
            assertEquals("незнайомий словнику жанр лишається як був", 1L, counts[mysticaBefore])
            assertEquals("уже канонічний жанр не рухається", 1L, counts["fantasy"])

            assertEquals(
                "заява мусить переїхати разом із рядком",
                12L,
                db.query("SELECT COUNT(*) FROM genre_assertions WHERE genreId='horror'").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
            )

            // Нагорода справді відкривається після міграції — це і є сенс зрізу.
            val snapshot = RoomAchievementProgressSource(
                achievementDao, RoomAchievementStore(achievementDao), emptySet()
            ).observe().first()
            assertTrue(
                "«10 книг у жанрі» мусить відкритись на наявних заявах",
                AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }.contains("genre_horror_10")
            )

            // Повторний прогін нічого не рухає.
            AudiobookDatabase.renormalizeGenreClaims(db)
            assertEquals(
                "ідемпотентність: другий прогін не змінює числа",
                counts,
                achievementDao.observeGenreBookCounts().first().associate { it.genreId to it.works }
            )
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private fun schema(fileName: String): JSONObject {
        val relative = "schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/$fileName"
        return JSONObject(listOf(File(relative), File("app/$relative")).first { it.isFile }.readText())
            .getJSONObject("database")
    }

    private fun createFromSchema(db: SupportSQLiteDatabase, schema: JSONObject) {
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
    }
}
