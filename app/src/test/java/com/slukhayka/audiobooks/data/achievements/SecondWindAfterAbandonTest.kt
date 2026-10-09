package com.slukhayka.audiobooks.data.achievements

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventKind
import com.slukhayka.audiobooks.data.db.ReadthroughMapping
import com.slukhayka.audiobooks.data.entries.AbandonUndo
import com.slukhayka.audiobooks.data.entries.AbandonedBooks
import com.slukhayka.audiobooks.data.entries.ReadingFormat
import com.slukhayka.audiobooks.data.entries.ReadingState
import com.slukhayka.audiobooks.data.entries.ReadthroughPolicy
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1174 (друга смуга, US22) — «Друге дихання»'s SECOND honest path: the
 * listener finished a book that still carried the «покинуто» mark when it
 * ended. ONE award, two paths; the first (two `COMPLETED` events on one book)
 * is `SecondWindAwardTest`.
 *
 * The path is not derivable from the rows afterwards — clearing the mark is
 * exactly what destroys the proof — so the fact is captured in the completion's
 * own step, while the pass still reads ABANDONED
 * (`AbandonedBooks.finish`, and the ordering has its own case in
 * `AbandonedBooksRoomTest`). Every case here runs the REAL store and the REAL
 * progress source, because the question is whether the fact reaches the
 * achievement pipeline at all: a snapshot hand-fed with the field would prove
 * only the evaluator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecondWindAfterAbandonTest {

    private lateinit var database: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    /** The mark owner itself — the same flow production feeds the snapshot. */
    private val abandoned by lazy { abandonedBooks() }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AudiobookDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.audiobookDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `finishing a marked book opens the award through the recorded fact`() = runBlocking {
        seed(bookId = BOOK_ID)
        markAbandoned(BOOK_ID)
        // One completion only, so the FIRST path cannot be what opens it.
        complete(BOOK_ID)

        val capturedWhileMarked = mutableListOf<String?>()
        val cleared = abandoned.finish(BOOK_ID) {
            // The capture reads the row the way the award's proof does: the mark
            // has to be there at THIS moment, because the write below is what
            // stops the pass from saying so.
            capturedWhileMarked += passState(BOOK_ID)
            store().recordFact(AchievementFact.FINISHED_AFTER_ABANDON)
        }

        assertTrue("позначку знято завершенням", cleared)
        assertEquals(
            "факт піймано саме тоді, коли начинка ще читалась як покинута",
            listOf(ABANDONED_STATE),
            capturedWhileMarked
        )
        val snapshot = snapshot()
        assertEquals("факт записано, і він читається", 1L, snapshot.booksFinishedAfterAbandon)
        assertEquals("другого завершення на цій книзі немає", 0L, snapshot.booksFinishedTwice)
        assertTrue("«Друге дихання» мусить відкритись другим шляхом", AWARD in earned(snapshot))
    }

    /**
     * The boundary the reading draws: the mark has to be THERE at the moment of
     * completion. A mark the listener took back themselves BEFORE finishing is
     * not «завершив після позначки» — by the time the book ended, nothing said
     * it had ever been abandoned, and the app does not reconstruct that story
     * (ADR-0014).
     */
    @Test
    fun `a mark taken back before the finish is not the second path`() = runBlocking {
        seed(bookId = BOOK_ID)
        markAbandoned(BOOK_ID)
        clearMark(BOOK_ID)
        complete(BOOK_ID)

        val cleared = abandoned.finish(BOOK_ID) {
            store().recordFact(AchievementFact.FINISHED_AFTER_ABANDON)
        }

        assertFalse("знімати вже нічого", cleared)
        val snapshot = snapshot()
        assertEquals(0L, snapshot.booksFinishedAfterAbandon)
        assertFalse("завершення книги, яку на той момент не кидали, нагороди не дає", AWARD in earned(snapshot))
    }

    /**
     * ADR-0060 — the manual «Прослухано» mark is NOT a completion for the
     * awards. It is the other door that passes through [AbandonedBooks.finish]
     * (the state would otherwise contradict itself), and it deliberately
     * captures nothing: a hand-set flag must not open an award about really
     * finishing a book.
     */
    @Test
    fun `the manual listened mark clears the mark without opening the award`() = runBlocking {
        seed(bookId = BOOK_ID)
        markAbandoned(BOOK_ID)

        // The manual door's call shape: no capture at all.
        val cleared = abandoned.finish(BOOK_ID)

        assertTrue("позначку знято", cleared)
        val snapshot = snapshot()
        assertEquals("але факту ручна позначка не пише", 0L, snapshot.booksFinishedAfterAbandon)
        assertFalse("«Друге дихання» не відкривається ручною позначкою", AWARD in earned(snapshot))
    }

    @Test
    fun `a book nobody abandoned never captures the fact`() = runBlocking {
        seed(bookId = BOOK_ID)
        complete(BOOK_ID)
        var captures = 0

        val cleared = abandoned.finish(BOOK_ID) { captures++ }

        assertFalse(cleared)
        assertEquals(0, captures)
        assertEquals("факт не записано", 0L, snapshot().booksFinishedAfterAbandon)
        assertFalse(AWARD in earned(snapshot()))
    }

    /**
     * The metric ORs the two paths — either one is the return the award is
     * about — and stays shut when NEITHER happened, which is the ordinary first
     * finish of a book nobody put down.
     */
    @Test
    fun `the award metric reads either path and opens on one of them`() {
        assertEquals(
            0L,
            AchievementMetric.SECOND_WIND.value(AchievementProgress())
        )
        assertEquals(
            "перший шлях сам собою",
            2L,
            AchievementMetric.SECOND_WIND.value(AchievementProgress(booksFinishedTwice = 2))
        )
        assertEquals(
            "другий шлях сам собою",
            1L,
            AchievementMetric.SECOND_WIND.value(AchievementProgress(booksFinishedAfterAbandon = 1))
        )
    }

    private fun store() = RoomAchievementStore(database.achievementDao())

    private fun earned(snapshot: AchievementProgress): List<String> =
        AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }

    private suspend fun snapshot(): AchievementProgress = RoomAchievementProgressSource(
        dao = database.achievementDao(),
        store = store(),
        registeredSourceIds = emptySet()
    ).observe().first()

    private suspend fun seed(bookId: String) {
        dao.insertAudiobooks(listOf(TestDataFactory.dataBooks().first().copy(id = bookId, title = "Книга $bookId")))
    }

    private suspend fun complete(bookId: String) {
        dao.insertPlaybackEvent(
            PlaybackEventEntity(bookId = bookId, kind = PlaybackEventKind.COMPLETED, timestamp = BASE)
        )
    }

    /** One live «покинуто» mark, written the shape the abandon door writes. */
    private suspend fun markAbandoned(bookId: String) {
        with(ReadthroughMapping) { dao.upsertReadthrough(pass(bookId, ReadingState.ABANDONED).toEntity()) }
    }

    /** What the pass reads RIGHT NOW — the capture's own view of the mark. */
    private suspend fun passState(bookId: String): String? =
        dao.readthroughsForEntry(bookId).firstOrNull { it.format == AUDIO_FORMAT }?.state

    /** The mark gone the way a cancel takes it away — without a completion. */
    private suspend fun clearMark(bookId: String) {
        with(ReadthroughMapping) { dao.upsertReadthrough(pass(bookId, ReadingState.IN_PROGRESS).toEntity()) }
    }

    private fun pass(bookId: String, state: ReadingState) = ReadthroughPolicy.start(
        id = AbandonedBooks.readthroughId(bookId),
        libraryEntryId = bookId,
        workId = "work-$bookId",
        format = ReadingFormat.AUDIO,
        startedAt = 1L,
        editionId = "edition-$bookId"
    )!!.copy(state = state)

    private fun abandonedBooks() = AbandonedBooks(
        dao = dao,
        listeningState = ListeningStateStore(dao),
        undo = object : AbandonUndo {
            override fun remember(bookId: String, before: AbandonUndo.BeforeMark) = Unit
            override fun recall(bookId: String): AbandonUndo.BeforeMark? = null
            override fun forget(bookId: String) = Unit
        },
        now = { BASE }
    )

    private companion object {
        const val BOOK_ID = "entry-1"
        const val AWARD = "second_wind"
        const val AUDIO_FORMAT = "AUDIO"
        const val ABANDONED_STATE = "ABANDONED"
        const val BASE = 1_700_000_000_000L
    }
}
