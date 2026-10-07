package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
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
 * #700 (T2) — «Друге дихання»: the SAME book finished a SECOND time.
 *
 * The ticket words the award as «завершення після повернення». What the recorded
 * data proves is narrower — two `COMPLETED` events on one book, and those are
 * two listening cycles because the player's `completionLogged` resets only when
 * a cycle ends, never inside one (`AudioPlayerManager`). The rejected reading
 * has its own case below: `RELISTEN` then `COMPLETED` must NOT open the award,
 * since «Почати спочатку» writes `RELISTEN` on a book that was never finished.
 *
 * Every case writes REAL rows into an in-memory Room: the proof lives in the SQL
 * (a correlated subquery on `playback_events`), so a hand-built snapshot would
 * test the evaluator alone and say nothing about the query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class SecondWindAwardTest {

    /** A fixed instant: no case here depends on the day it runs. */
    private val base = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test fun `one completion is not a second wind`() = runBlocking {
        val snapshot = snapshotOf { database ->
            val dao = database.audiobookDao()
            val book = TestDataFactory.dataBooks().first()
            dao.insertAudiobooks(listOf(book))
            dao.insertPlaybackEvent(
                PlaybackEventEntity(bookId = book.id, kind = PlaybackEventKind.COMPLETED, timestamp = base)
            )
        }

        assertEquals("одне завершення — ще не вдруге", 0L, snapshot.booksFinishedTwice)
        assertFalse(
            "«Друге дихання» не має відкриватись на порозі − 1",
            "second_wind" in earnedFrom(snapshot)
        )
    }

    @Test fun `finishing the same book twice opens the award`() = runBlocking {
        val snapshot = snapshotOf { database ->
            val dao = database.audiobookDao()
            val book = TestDataFactory.dataBooks().first()
            dao.insertAudiobooks(listOf(book))
            // Two completions of the SAME book, a month apart: two cycles.
            dao.insertPlaybackEvent(
                PlaybackEventEntity(bookId = book.id, kind = PlaybackEventKind.COMPLETED, timestamp = base)
            )
            dao.insertPlaybackEvent(
                PlaybackEventEntity(
                    bookId = book.id, kind = PlaybackEventKind.COMPLETED, timestamp = base + 30 * day
                )
            )
        }

        assertEquals("два цикли на одній книзі", 1L, snapshot.booksFinishedTwice)
        assertTrue("«Друге дихання» мусить відкритись", "second_wind" in earnedFrom(snapshot))
    }

    /**
     * The false positive this SQL has to resist: two DIFFERENT books finished
     * once each are not one book finished twice.
     */
    @Test fun `completions on different books are not a second wind`() = runBlocking {
        val snapshot = snapshotOf { database ->
            val dao = database.audiobookDao()
            val books = TestDataFactory.dataBooks().take(2)
            dao.insertAudiobooks(books)
            books.forEachIndexed { index, book ->
                dao.insertPlaybackEvent(
                    PlaybackEventEntity(
                        bookId = book.id, kind = PlaybackEventKind.COMPLETED, timestamp = base + index * day
                    )
                )
            }
        }

        assertEquals("дві книги по одному завершенню — не переслух", 0L, snapshot.booksFinishedTwice)
        assertFalse(
            "«Друге дихання» не має відкриватись на двох різних книгах",
            "second_wind" in earnedFrom(snapshot)
        )
    }

    /**
     * The rejected reading, pinned as a decision: «Почати спочатку» on an
     * UNFINISHED book writes `RELISTEN` with no completion before it, so the
     * `RELISTEN → COMPLETED` pair says nothing about a return. The positive
     * control («Переслух» opens) proves the rows were really written.
     */
    @Test fun `a restart followed by a completion is not a second wind`() = runBlocking {
        val snapshot = snapshotOf { database ->
            val dao = database.audiobookDao()
            val book = TestDataFactory.dataBooks().first()
            dao.insertAudiobooks(listOf(book))
            dao.insertPlaybackEvent(
                PlaybackEventEntity(bookId = book.id, kind = PlaybackEventKind.RELISTEN, timestamp = base)
            )
            dao.insertPlaybackEvent(
                PlaybackEventEntity(
                    bookId = book.id, kind = PlaybackEventKind.COMPLETED, timestamp = base + 30 * day
                )
            )
        }

        val earned = earnedFrom(snapshot)
        assertTrue("переслух справді записано — «Переслух» відкрито", "relisten_1" in earned)
        assertEquals("RELISTEN не робить завершення другим", 0L, snapshot.booksFinishedTwice)
        assertFalse("«Друге дихання» не має відкриватись після «Почати спочатку»", "second_wind" in earned)
    }

    @Test fun `an earned second wind is never handed out again`() = runBlocking {
        val snapshot = snapshotOf { database ->
            val dao = database.audiobookDao()
            val book = TestDataFactory.dataBooks().first()
            dao.insertAudiobooks(listOf(book))
            dao.insertPlaybackEvent(
                PlaybackEventEntity(bookId = book.id, kind = PlaybackEventKind.COMPLETED, timestamp = base)
            )
            dao.insertPlaybackEvent(
                PlaybackEventEntity(
                    bookId = book.id, kind = PlaybackEventKind.COMPLETED, timestamp = base + 30 * day
                )
            )
        }

        val first = earnedFrom(snapshot)
        assertTrue("перший прогін видає «Друге дихання»", "second_wind" in first)

        val second = AchievementEvaluator.evaluate(snapshot, first.toSet()).map { it.id }
        assertFalse("повторний прогін не видає «Друге дихання» вдруге", "second_wind" in second)
        assertTrue("повторний прогін не видає нічого з уже здобутого", second.none { it in first })
    }

    /** The catalogue slot: third on the relisten ladder, opens at one book. */
    @Test fun `the award sits third on the relisten ladder and opens at one book`() {
        assertEquals(
            "група «relisten» тримає рівні 1, 2, 3 у цьому порядку",
            listOf("relisten_1" to 1, "relisten_5" to 2, "second_wind" to 3),
            AchievementCatalog.definitions.filter { it.group == "relisten" }.map { it.id to it.level }
        )
        val definition = AchievementCatalog.definitions.single { it.id == "second_wind" }
        assertEquals(AchievementMetric.BOOKS_FINISHED_TWICE, definition.metric)
        assertEquals(1L, definition.threshold)
        assertFalse("нагорода видима — вона не з прихованих", definition.hidden)
    }

    private fun earnedFrom(snapshot: AchievementProgress): List<String> =
        AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }

    private suspend fun snapshotOf(build: suspend (AudiobookDatabase) -> Unit): AchievementProgress {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        return try {
            build(database)
            RoomAchievementProgressSource(
                database.achievementDao(), RoomAchievementStore(database.achievementDao()), emptySet()
            ).observe().first()
        } finally {
            database.close()
        }
    }
}
