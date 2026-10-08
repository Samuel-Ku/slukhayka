package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventKind
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #701 — «Англомовний старт».
 *
 * Two real facts must both be true: a recorded `RESUME` for the book, and a
 * rendition whose language resolves to the canonical BCP-47 `en`. The ticket
 * names the award but sets no threshold, and the spec does not mention it at
 * all — so the smallest honest one, the FIRST English start, is pinned by the
 * table test below: a later change must be a decision, not drift.
 *
 * Runs against REAL rows in an in-memory Room, because the honesty lives in
 * the join (`editions.workId` is the book) and in `LanguageCode.normalize`,
 * not in a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EnglishStartAwardTest {

    private val base = 1_700_000_000_000L

    private fun <T> withDatabase(block: suspend (AudiobookDatabase) -> T): T = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    /** Real library rows: an audiobook plus its Library Entry. */
    private suspend fun libraryBooks(database: AudiobookDatabase, count: Int): List<String> {
        val dao = database.audiobookDao()
        val first = TestDataFactory.dataBooks().first()
        val rows = (0 until count).map { first.copy(id = "en-book-$it", title = "Книга $it") }
        dao.insertAudiobooks(rows)
        for (row in rows) dao.upsertLibraryEntry(row.id, row.id, false, 1L, 0f)
        return rows.map { it.id }
    }

    /** The rendition with the language claim; `workId` is the Library Entry id. */
    private suspend fun rendition(database: AudiobookDatabase, bookId: String, language: String): String {
        val id = "edition-$bookId-$language"
        database.audiobookDao().insertEdition(
            EditionEntity(id = id, workId = bookId, language = language)
        )
        return id
    }

    private suspend fun event(
        database: AudiobookDatabase,
        bookId: String,
        kind: String,
        at: Long
    ) {
        database.audiobookDao().insertPlaybackEvent(
            PlaybackEventEntity(bookId = bookId, kind = kind, timestamp = at)
        )
    }

    private suspend fun started(database: AudiobookDatabase, bookId: String, at: Long) =
        event(database, bookId, PlaybackEventKind.RESUME, at)

    private suspend fun snapshot(database: AudiobookDatabase): AchievementProgress =
        RoomAchievementProgressSource(
            database.achievementDao(), RoomAchievementStore(database.achievementDao()), emptySet()
        ).observe().first()

    private suspend fun earned(database: AudiobookDatabase): List<String> =
        AchievementEvaluator.evaluate(snapshot(database), emptySet()).map { it.id }

    /**
     * The whole `languages` ladder as data: the two awards merged in the T3
     * slice keep their rungs, and «Англомовний старт» becomes the third one.
     * The threshold is 1 — the ticket gives none, so "the first English start"
     * is the pinned reading.
     */
    @Test
    fun `the languages ladder pins english start as its third rung`() {
        val ladder = AchievementCatalog.definitions
            .filter { it.group == "languages" }
            .map { Triple(it.id, it.level, it.threshold) }

        assertEquals(
            listOf(
                Triple("bilingual_2", 1, 2L),
                Triple("polyglot_3", 2, 3L),
                Triple("english_start", 3, 1L)
            ),
            ladder
        )
    }

    @Test
    fun `a real English rendition with a start opens the award`() = withDatabase { database ->
        val book = libraryBooks(database, 1).single()
        rendition(database, book, "en")
        started(database, book, base)

        assertEquals("одна книга з реальним кодом en", 1L, snapshot(database).englishStartBooks)
        assertTrue(
            "«Англомовний старт» мусить відкритись на першому англійському старті",
            "english_start" in earned(database)
        )
    }

    @Test
    fun `a start without an English rendition does not open the award`() = withDatabase { database ->
        val books = libraryBooks(database, 2)
        rendition(database, books[0], "uk")
        rendition(database, books[1], "de")
        books.forEachIndexed { index, book -> started(database, book, base + index) }

        assertEquals("українська й німецька начитки — не англійська", 0L, snapshot(database).englishStartBooks)
        assertFalse(
            "нагорода не має відкриватись на інших мовах",
            "english_start" in earned(database)
        )
    }

    /**
     * An unknown language stays unknown: the empty claim is dropped in SQL, a
     * whitespace-only one and a name nobody can map resolve to `null` in the
     * normalizer, and none of them may count (ADR-0014).
     */
    @Test
    fun `an empty or unmappable language is not English`() = withDatabase { database ->
        val books = libraryBooks(database, 3)
        rendition(database, books[0], "")
        rendition(database, books[1], "Klingon")
        rendition(database, books[2], "   ")
        books.forEachIndexed { index, book -> started(database, book, base + index) }

        assertEquals("порожня й невідома мова не рахуються", 0L, snapshot(database).englishStartBooks)
        assertFalse(
            "нагорода не має відкриватись на невідомій мові",
            "english_start" in earned(database)
        )
    }

    /**
     * A source reports a language as TEXT as often as it reports a code, which
     * is exactly why the snapshot maps the claim through `LanguageCode` instead
     * of comparing strings. Without that normalization the raw `English` would
     * stop matching `en` and this test would fail.
     */
    @Test
    fun `a textual claim resolves through the one normalizer`() = withDatabase { database ->
        val book = libraryBooks(database, 1).single()
        rendition(database, book, "English")
        started(database, book, base)

        assertEquals("текстова заява мапиться в код en", 1L, snapshot(database).englishStartBooks)
        assertTrue("«Англомовний старт» мусить відкритись", "english_start" in earned(database))
    }

    /** Only a START counts: finishing an English book is a different fact. */
    @Test
    fun `a completion is not a start`() = withDatabase { database ->
        val book = libraryBooks(database, 1).single()
        rendition(database, book, "en")
        event(database, book, PlaybackEventKind.COMPLETED, base)

        assertEquals("завершення не є стартом", 0L, snapshot(database).englishStartBooks)
        assertFalse(
            "без RESUME нагорода не має відкриватись",
            "english_start" in earned(database)
        )
    }

    /**
     * The unit is the BOOK: two English renditions of one book are one English
     * start, and a full tag (`en-US`) is still the `en` primary tag. A
     * row-counting bug would report three here.
     */
    @Test
    fun `the count is books, not renditions`() = withDatabase { database ->
        val books = libraryBooks(database, 2)
        rendition(database, books[0], "en")
        rendition(database, books[0], "English")
        rendition(database, books[1], "en-US")
        books.forEachIndexed { index, book -> started(database, book, base + index) }

        assertEquals(
            "дві начитки однієї книги — це одна книга",
            2L, snapshot(database).englishStartBooks
        )
        assertTrue("нагорода мусить відкритись", "english_start" in earned(database))
    }

    /**
     * The published decision this test keeps a DECISION: the unit is the BOOK.
     *
     * `playback_events` carries no `editionId`, so a start cannot be attributed
     * to one rendition — a book that has BOTH a Ukrainian and an English
     * rendition counts as an English start once the listener started it. A
     * future change of that rule (say, «only a purely English book») has to
     * change this test first.
     */
    @Test
    fun `a book with both Ukrainian and English renditions counts once`() = withDatabase { database ->
        val book = libraryBooks(database, 1).single()
        rendition(database, book, "uk")
        rendition(database, book, "en")
        started(database, book, base)

        assertEquals("книга, а не начитка", 1L, snapshot(database).englishStartBooks)
        assertTrue(
            "«Англомовний старт» мусить відкритись на книзі з англійською начиткою",
            "english_start" in earned(database)
        )
    }

    /**
     * Only the rendition's OWN claim is read (`editions.language`), never the
     * shared `edition_facets`: here the facet knows English while the Edition
     * itself says nothing, and that must NOT open the award. The limitation is
     * documented in `EnglishStart` and pinned here so it stays visible.
     */
    @Test
    fun `a language known only from a shared facet does not count`() = withDatabase { database ->
        val book = libraryBooks(database, 1).single()
        val editionId = rendition(database, book, "")
        database.audiobookDao().mergeEditionFacet(
            editionId = editionId, workId = book, narratorId = null, language = "en",
            durationSeconds = null, durationBucketId = null, chapterCount = null,
            isAbridged = null, availabilityAvailable = null, availabilityObservedAtMillis = null,
            availabilityTtlSeconds = null, updatedAt = base
        )
        started(database, book, base)

        assertEquals(
            "фасет — не заява начитки",
            0L, snapshot(database).englishStartBooks
        )
        assertFalse(
            "нагорода не має відкриватись на мові зі спільного фасета",
            "english_start" in earned(database)
        )
    }
}
