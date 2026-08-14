package com.example.data.db

import android.content.Context
import androidx.room.Room
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
 * spec-20 T3 (#124) — the one-time legacy placeholder cleanup: a single
 * idempotent UPDATE (no schema change) strips the branded placeholders
 * (author «4read.org», narrator «4read Voice Narrator», genre «4read
 * Каталог», branded description templates and URLs) from existing rows and
 * leaves real values untouched. Runs twice to prove idempotency; the pure
 * rules are pinned by PlaceholderScrubTest, this pins the SQL mirror.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyPlaceholderCleanupDaoTest {

    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertRow(
        id: String,
        author: String,
        narrator: String,
        genre: String,
        description: String
    ) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = id,
                    title = "Книга $id",
                    author = author,
                    narrator = narrator,
                    description = description,
                    coverDrawableRes = 0,
                    sourceUrl = "https://4read.org/$id.html",
                    genre = genre
                )
            )
        )
    }

    @Test
    fun `scrub blanks branded placeholders and keeps real values`() = runBlocking {
        insertRow(
            id = "legacy",
            author = "4read.org",
            narrator = "4read Voice Narrator",
            genre = "4read Каталог",
            description = "Аудіокнига з каталогу 4read.org. Джерело: https://4read.org/7589-x.html"
        )
        insertRow(
            id = "real",
            author = "Тарас Шевченко",
            narrator = "Валерій Завалко",
            genre = "Фантастика",
            description = "Звичайний опис"
        )
        // The SQL mirrors the Kotlin rules exactly: http:// scheme (not just
        // https://) and surrounding whitespace are scrubbed too — a legacy row
        // carrying them must end up identical to the pure-JVM result.
        insertRow(
            id = "legacy_http",
            author = "Аудиокнига 4read.org",
            narrator = "4read Voice Narrator",
            genre = "4read Каталог",
            description = "  Аудіокнига з каталогу 4read.org. Джерело: http://4read.org/121-x.html  "
        )

        dao.scrubLegacyPlaceholders()

        val legacy = dao.getAudiobookById("legacy")!!
        assertEquals("", legacy.author)
        assertEquals("", legacy.narrator)
        assertEquals("", legacy.genre)
        assertEquals("Джерело: 7589-x.html", legacy.description)
        // The book's own source address is an entity, never scrubbed.
        assertEquals("https://4read.org/legacy.html", legacy.sourceUrl)

        // Same rules as PureScrub.description would produce — http variant,
        // trimmed.
        val legacyHttp = dao.getAudiobookById("legacy_http")!!
        assertEquals("", legacyHttp.author)
        assertEquals("", legacyHttp.genre)
        assertEquals("Джерело: 121-x.html", legacyHttp.description)

        val real = dao.getAudiobookById("real")!!
        assertEquals("Тарас Шевченко", real.author)
        assertEquals("Валерій Завалко", real.narrator)
        assertEquals("Фантастика", real.genre)
        assertEquals("Звичайний опис", real.description)
    }

    @Test
    fun `scrub is idempotent`() = runBlocking {
        insertRow(
            id = "legacy",
            author = "4read.org",
            narrator = "4read narrator",
            genre = "4read Каталог",
            description = "Аудіокнига з джерела 4read. Джерело: https://4read.org/kobzar.html"
        )

        dao.scrubLegacyPlaceholders()
        val afterFirst = dao.getAudiobookById("legacy")!!
        assertEquals("", afterFirst.author)
        assertEquals("Джерело: kobzar.html", afterFirst.description)

        dao.scrubLegacyPlaceholders()
        val afterSecond = dao.getAudiobookById("legacy")!!
        assertEquals("", afterSecond.author)
        assertEquals("Джерело: kobzar.html", afterSecond.description)
    }

    @Test
    fun `scrub leaves a fully clean library untouched`() = runBlocking {
        insertRow(
            id = "clean",
            author = "Автор",
            narrator = "Читець",
            genre = "Жанр",
            description = "Чистий опис"
        )

        dao.scrubLegacyPlaceholders()
        dao.scrubLegacyPlaceholders()

        val row = dao.getAudiobookById("clean")!!
        assertEquals("Автор", row.author)
        assertTrue(dao.getAllAudiobooksOnce().isNotEmpty())
    }
}