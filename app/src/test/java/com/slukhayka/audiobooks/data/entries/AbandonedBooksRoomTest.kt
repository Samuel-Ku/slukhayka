package com.slukhayka.audiobooks.data.entries

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.ReadthroughEntity
import com.slukhayka.audiobooks.data.db.ReadthroughMapping
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-52 US28 / #1174 — the write path of «покинути книгу» against **in-memory
 * Room**: the action is proved through the real SQL doors. The mark UPSERTS the
 * deterministic `rt-audio-<entryId>` pass (a repeat call can never fork a
 * second one), the live position stays in Listening State, and the cancel puts
 * the book back EXACTLY as it was — the pass the mark created leaves with it,
 * and a pass that existed returns to the state the undo note remembers.
 *
 * The door's refusals are pinned here too: no position, a finished book (the
 * flag AND the position at the end — the boundary the library calls
 * «Завершені»), a pass only history is left of, and a row the strict mapping
 * cannot read (never overwritten, ADR-0014).
 *
 * The undo note is the real `SharedPreferencesAbandonUndo`: its durability is
 * part of the AC, so one test reads it back through a FRESH `AbandonedBooks`,
 * exactly as a restart would.
 *
 * #1174 (друга смуга) adds the completion side of the same door: `finish` takes
 * the mark away through the reading policy and captures the
 * «завершив після покинутого» **before** the row stops carrying the mark. That
 * order has its own case here, because it is the one fact the write destroys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AbandonedBooksRoomTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var listening: ListeningStateStore
    private lateinit var undo: AbandonUndo
    private lateinit var abandonedBooks: AbandonedBooks

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        listening = ListeningStateStore(dao)
        undo = SharedPreferencesAbandonUndo(context)
        undo.forget(BOOK_ID)
        abandonedBooks = AbandonedBooks(dao, listening, undo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A book imported after the 45->46 backfill: a card and NO readthrough. */
    private suspend fun seedImportedBook(bookId: String = BOOK_ID, workId: String = WORK_ID) {
        dao.upsertWork(WorkEntity(id = workId, mergeKey = workId, title = TITLE, author = AUTHOR))
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = TITLE,
                    author = AUTHOR,
                    narrator = "Диктор",
                    description = "",
                    coverDrawableRes = 0,
                    genre = "",
                    sourceUrl = "https://4read.org/1.html",
                    totalDurationSeconds = BOOK_TOTAL_SECONDS,
                    totalChapters = CHAPTER_DURATIONS.size
                )
            )
        )
        dao.insertLibraryEntryWithOrigin(
            id = bookId,
            workId = workId,
            origin = LibraryEntryOrigin.EXPLICIT_SAVE.name,
            createdAt = ENTERED_AT
        )
        dao.insertEdition(
            EditionEntity(
                id = EDITION_ID,
                workId = bookId,
                narrator = "Диктор",
                language = "uk",
                totalChapters = CHAPTER_DURATIONS.size,
                totalDurationSeconds = BOOK_TOTAL_SECONDS
            )
        )
        dao.insertChapters(
            CHAPTER_DURATIONS.mapIndexed { index, durationSeconds ->
                ChapterEntity(
                    id = "ch-$index",
                    bookId = bookId,
                    chapterIndex = index,
                    title = "Розділ ${index + 1}",
                    durationSeconds = durationSeconds,
                    editionId = EDITION_ID
                )
            }
        )
        assertTrue(
            "an import writes no pass of its own — the action must create one",
            dao.readthroughsForEntry(bookId).isEmpty()
        )
    }

    /** A real listening position, written the way playback writes it. */
    private suspend fun listen(
        chapterIndex: Int = 0,
        positionSeconds: Long = 120L,
        completed: Boolean = false
    ) {
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = EDITION_ID,
                bookId = BOOK_ID,
                currentChapterIndex = chapterIndex,
                currentPositionSeconds = positionSeconds,
                lastListenedAt = 1_700_000_000_000L,
                isCompleted = completed
            )
        )
    }

    private suspend fun seedPass(
        state: ReadingState,
        id: String = AbandonedBooks.readthroughId(BOOK_ID)
    ) {
        val pass = ReadthroughPolicy.start(
            id = id,
            libraryEntryId = BOOK_ID,
            workId = WORK_ID,
            format = ReadingFormat.AUDIO,
            startedAt = 5L,
            editionId = EDITION_ID,
            value = 600
        )!!.copy(state = state)
        with(ReadthroughMapping) { dao.upsertReadthrough(pass.toEntity()) }
    }

    private suspend fun onlyPass(): ReadthroughEntity = dao.readthroughsForEntry(BOOK_ID).single()

    /** Writes the note the way a damaged store would have left it. */
    private fun corruptNote(value: String) {
        context.getSharedPreferences("abandon_undo", Context.MODE_PRIVATE)
            .edit()
            .putString("before:$BOOK_ID", value)
            .commit()
    }

    @Test
    fun `abandon upserts ONE audio pass under the deterministic id`() = runBlocking {
        seedImportedBook()
        listen()

        val result = abandonedBooks.abandon(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(ReadingState.ABANDONED), result)
        // The mark brought this pass into being — the seed proved there was none
        // and onlyPass() proves there is now exactly one, under the fixed id.
        val row = onlyPass()
        assertEquals("rt-audio-$BOOK_ID", row.id)
        assertEquals(AUDIO_FORMAT, row.format)
        assertEquals(ABANDONED_STATE, row.state)
        assertEquals(WORK_ID, row.workId)
        assertEquals("the pass names the Edition the Listening State names", EDITION_ID, row.editionId)
        assertEquals("the entry's own createdAt is the pass's moment", ENTERED_AT, row.startedAt)
        assertNull("abandoning is not finishing", row.finishedAt)
        assertEquals("SECONDS", row.unit)
        assertEquals(
            "ADR-0046 §3 — the live position is NOT copied into the pass",
            0,
            row.unitValue
        )
        assertEquals("nothing is invented into the journal", "[]", row.journalJson)
    }

    @Test
    fun `cancel removes the pass the mark itself created`() = runBlocking {
        seedImportedBook()
        listen()
        abandonedBooks.abandon(BOOK_ID)

        val result = abandonedBooks.cancel(BOOK_ID)

        assertEquals(
            "the book is left exactly as it was: no pass at all",
            AbandonedBooks.Result.Changed(state = null),
            result
        )
        assertTrue(
            "no phantom readthrough on «Мій рік»",
            dao.readthroughsForEntry(BOOK_ID).isEmpty()
        )
        assertEquals(
            "and the position was never touched",
            120L,
            listening.getProgressSync(BOOK_ID)!!.currentPositionSeconds
        )
    }

    @Test
    fun `cancel restores a pass that was PLANNED before the mark`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.PLANNED)
        listen()

        abandonedBooks.abandon(BOOK_ID)
        val result = abandonedBooks.cancel(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(ReadingState.PLANNED), result)
        assertEquals("exactly as it was, not as the evidence reads", PLANNED_STATE, onlyPass().state)
    }

    @Test
    fun `cancel restores an existing pass and keeps its moment and units`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.IN_PROGRESS)
        listen()
        abandonedBooks.abandon(BOOK_ID)

        val result = abandonedBooks.cancel(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(ReadingState.IN_PROGRESS), result)
        val row = onlyPass()
        assertEquals(IN_PROGRESS_STATE, row.state)
        assertEquals("the pass keeps the moment it began", 5L, row.startedAt)
        assertEquals("and the units it already knew", 600, row.unitValue)
        assertEquals(120L, listening.getProgressSync(BOOK_ID)!!.currentPositionSeconds)
    }

    @Test
    fun `an abandon and its cancel survive a restart - the note is durable`() = runBlocking {
        seedImportedBook()
        listen()
        abandonedBooks.abandon(BOOK_ID)

        // A restart: a brand-new module reading the SAME durable note.
        val afterRestart = AbandonedBooks(dao, listening, SharedPreferencesAbandonUndo(context))
        val result = afterRestart.cancel(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(state = null), result)
        assertTrue(dao.readthroughsForEntry(BOOK_ID).isEmpty())
        assertNull("the consumed note is gone", SharedPreferencesAbandonUndo(context).recall(BOOK_ID))
    }

    @Test
    fun `the live position stays in Listening State after the mark`() = runBlocking {
        seedImportedBook()
        listen(chapterIndex = 1, positionSeconds = 300L)

        abandonedBooks.abandon(BOOK_ID)

        val progress = listening.getProgressSync(BOOK_ID)!!
        assertEquals(1, progress.currentChapterIndex)
        assertEquals(300L, progress.currentPositionSeconds)
    }

    @Test
    fun `a repeated abandon rewrites the same row and keeps the first note`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.IN_PROGRESS)
        listen()

        abandonedBooks.abandon(BOOK_ID)
        val second = abandonedBooks.abandon(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(ReadingState.ABANDONED), second)
        assertEquals("still exactly one pass", 1, dao.readthroughsForEntry(BOOK_ID).size)
        assertEquals(
            "the note still describes the pass BEFORE any mark",
            AbandonUndo.BeforeMark(existed = true, state = ReadingState.IN_PROGRESS),
            undo.recall(BOOK_ID)
        )
    }

    @Test
    fun `an existing audio pass under another id is marked instead of shadowed`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.IN_PROGRESS, id = FOREIGN_PASS_ID)
        listen()

        val result = abandonedBooks.abandon(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(ReadingState.ABANDONED), result)
        val row = onlyPass()
        assertEquals("no second pass is forked", FOREIGN_PASS_ID, row.id)
        assertEquals(ABANDONED_STATE, row.state)
    }

    @Test
    fun `a book with no listening position is refused and writes nothing`() = runBlocking {
        seedImportedBook()

        val result = abandonedBooks.abandon(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Refused(AbandonedBooks.REASON_NO_PROGRESS), result)
        assertTrue("no pass is invented", dao.readthroughsForEntry(BOOK_ID).isEmpty())
    }

    @Test
    fun `a finished book is refused`() = runBlocking {
        seedImportedBook()
        listen(completed = true)

        val result = abandonedBooks.abandon(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Refused(AbandonedBooks.REASON_FINISHED), result)
        assertTrue("no pass is invented", dao.readthroughsForEntry(BOOK_ID).isEmpty())
    }

    @Test
    fun `a book playing its last seconds is refused - the boundary the library uses`() = runBlocking {
        seedImportedBook()
        // The last chapter with the position already at the book's end: the
        // library counts this book «Завершена», so the door must too — even
        // though the manual flag is still false and playback is running.
        listen(
            chapterIndex = CHAPTER_DURATIONS.lastIndex,
            positionSeconds = CHAPTER_DURATIONS.last(),
            completed = false
        )

        val result = abandonedBooks.abandon(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Refused(AbandonedBooks.REASON_FINISHED), result)
        assertTrue("no mark is written", dao.readthroughsForEntry(BOOK_ID).isEmpty())
    }

    @Test
    fun `only a finished pass is left - history is not marked`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.FINISHED)
        listen()

        val result = abandonedBooks.abandon(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Refused(AbandonedBooks.REASON_FINISHED), result)
        assertEquals("history is untouched", FINISHED_STATE, onlyPass().state)
    }

    @Test
    fun `a pass row the app cannot read is never overwritten`() = runBlocking {
        seedImportedBook()
        listen()
        dao.upsertReadthrough(
            ReadthroughEntity(
                id = AbandonedBooks.readthroughId(BOOK_ID),
                libraryEntryId = BOOK_ID,
                workId = WORK_ID,
                format = AUDIO_FORMAT,
                state = "DROPPED",
                startedAt = 5L,
                finishedAt = null,
                editionId = EDITION_ID,
                unit = "SECONDS",
                unitValue = 0,
                journalJson = "[]"
            )
        )

        val result = abandonedBooks.abandon(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Refused(AbandonedBooks.REASON_UNREADABLE_PASS), result)
        assertEquals("the unreadable row is left exactly as it was", "DROPPED", onlyPass().state)
    }

    @Test
    fun `a note the app cannot read falls back to the state the evidence proves`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.PLANNED)
        listen()
        abandonedBooks.abandon(BOOK_ID)
        corruptNote("1|DROPPED")

        val result = abandonedBooks.cancel(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(ReadingState.IN_PROGRESS), result)
        assertEquals("the evidence decides, not a guessed state", IN_PROGRESS_STATE, onlyPass().state)
    }

    @Test
    fun `a note the app cannot read never claims a start nothing proves`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.PLANNED)
        listen()
        abandonedBooks.abandon(BOOK_ID)
        corruptNote("1|DROPPED")
        // Nothing proves a start any more: the fallback must not invent one.
        dao.deletePlaybackProgressForBook(BOOK_ID)

        val result = abandonedBooks.cancel(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(ReadingState.PLANNED), result)
        assertEquals(PLANNED_STATE, onlyPass().state)
    }

    @Test
    fun `cancel without the mark is refused`() = runBlocking {
        seedImportedBook()
        listen()

        val result = abandonedBooks.cancel(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Refused(AbandonedBooks.REASON_NOT_ABANDONED), result)
        assertTrue(dao.readthroughsForEntry(BOOK_ID).isEmpty())
    }

    @Test
    fun `the abandoned set follows the rows`() = runBlocking {
        seedImportedBook()
        listen()
        assertEquals(emptySet<String>(), abandonedBooks.observeAbandonedBookIds().first())

        abandonedBooks.abandon(BOOK_ID)
        assertEquals(setOf(BOOK_ID), abandonedBooks.observeAbandonedBookIds().first())

        abandonedBooks.cancel(BOOK_ID)
        assertEquals(emptySet<String>(), abandonedBooks.observeAbandonedBookIds().first())
    }

    // --- #1174 (друга смуга): завершення книги знімає позначку --------------

    @Test
    fun `finishing a marked book takes the mark away and leaves the pass finished`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.IN_PROGRESS)
        listen()
        abandonedBooks.abandon(BOOK_ID)

        val cleared = pinnedClock().finish(BOOK_ID)

        assertTrue("позначка була — її знято", cleared)
        val row = onlyPass()
        assertEquals("начинка знову завершена", FINISHED_STATE, row.state)
        assertEquals("момент завершення — той, яким його записав застосунок", FINISHED_AT, row.finishedAt)
        assertEquals("момент початку не вигадано", 5L, row.startedAt)
        assertEquals("одиниці, які начинка знала, лишаються", 600, row.unitValue)
        assertEquals("позиція в Listening State недоторкана", 120L, listening.getProgressSync(BOOK_ID)!!.currentPositionSeconds)
        assertEquals("і позначки більше немає на жодній поверхні", emptySet<String>(), abandonedBooks.observeAbandonedBookIds().first())
    }

    /**
     * The ORDER is the whole point of the write: the capture has to see the
     * mark, because after the rewrite the pass reads FINISHED and nothing says
     * it was ever abandoned. A capture that runs after the persist reads
     * `null` here (no ABANDONED pass is left), and the award loses its proof.
     */
    @Test
    fun `the capture sees the mark before the row stops carrying it`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.IN_PROGRESS)
        listen()
        abandonedBooks.abandon(BOOK_ID)
        val seen = mutableListOf<String?>()

        pinnedClock().finish(BOOK_ID) { seen += abandonedPassState() }

        assertEquals("факт зафіксовано рівно раз і саме тоді, коли позначка ще стояла", listOf(ABANDONED_STATE), seen)
        assertEquals("а вже потім начинка стала історією", FINISHED_STATE, onlyPass().state)
    }

    @Test
    fun `a book without the mark has nothing to take away and captures nothing`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.IN_PROGRESS)
        listen()
        var captures = 0

        val cleared = pinnedClock().finish(BOOK_ID) { captures++ }

        assertFalse("звичайне завершення книги, яку не кидали, — не подія", cleared)
        assertEquals("і факту воно не пише", 0, captures)
        assertEquals("начинка лишається як була", IN_PROGRESS_STATE, onlyPass().state)
    }

    @Test
    fun `history is left alone - a finished pass is never finished twice`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.FINISHED)
        listen()

        val cleared = pinnedClock().finish(BOOK_ID) { error("історія не пише фактів") }

        assertFalse("завершена начинка — вже історія", cleared)
        assertNull("і дати їй другий finishedAt нічим", onlyPass().finishedAt)
    }

    @Test
    fun `a row the app cannot read is left alone by the completion too`() = runBlocking {
        seedImportedBook()
        listen()
        dao.upsertReadthrough(
            ReadthroughEntity(
                id = AbandonedBooks.readthroughId(BOOK_ID),
                libraryEntryId = BOOK_ID,
                workId = WORK_ID,
                format = AUDIO_FORMAT,
                state = "DROPPED",
                startedAt = 5L,
                finishedAt = null,
                editionId = EDITION_ID,
                unit = "SECONDS",
                unitValue = 0,
                journalJson = "[]"
            )
        )

        val cleared = pinnedClock().finish(BOOK_ID) { error("нерозпізнаний рядок не пише фактів") }

        assertFalse("переписати рядок, який застосунок не класифікує, не можна (ADR-0014)", cleared)
        assertEquals("рядок лишається точно як був", "DROPPED", onlyPass().state)
    }

    @Test
    fun `the undo note goes with the mark it described`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.IN_PROGRESS)
        listen()
        abandonedBooks.abandon(BOOK_ID)
        assertEquals(
            "нотатка описує те, чим начинка була до позначки",
            AbandonUndo.BeforeMark(existed = true, state = ReadingState.IN_PROGRESS),
            undo.recall(BOOK_ID)
        )

        pinnedClock().finish(BOOK_ID)

        assertNull("позначки немає — нотатці нічого описувати", undo.recall(BOOK_ID))
    }

    /** A pass under ANOTHER id carries the live mark, and the completion finds it. */
    @Test
    fun `a mark on a pass under another id is taken away too`() = runBlocking {
        seedImportedBook()
        seedPass(ReadingState.IN_PROGRESS, id = FOREIGN_PASS_ID)
        listen()
        abandonedBooks.abandon(BOOK_ID)

        val cleared = pinnedClock().finish(BOOK_ID)

        assertTrue(cleared)
        val row = onlyPass()
        assertEquals("тая сама начинка, без другої", FOREIGN_PASS_ID, row.id)
        assertEquals(FINISHED_STATE, row.state)
    }

    /** The completion's own clock, pinned: `finishedAt` must not be a wall clock in tests. */
    private fun pinnedClock() = AbandonedBooks(dao, listening, undo, now = { FINISHED_AT })

    /** What the pass reads RIGHT NOW — the capture's own view of the mark. */
    private suspend fun abandonedPassState(): String? =
        dao.readthroughsForEntry(BOOK_ID).firstOrNull { it.format == AUDIO_FORMAT }?.state

    private companion object {
        const val BOOK_ID = "entry-1"
        const val WORK_ID = "work-1"
        const val EDITION_ID = "edition-1"
        const val FOREIGN_PASS_ID = "rt-manual:audio:work-1"
        const val TITLE = "Острів Дума"
        const val AUTHOR = "Тарас Шевченко"
        const val ENTERED_AT = 1_700_000_000_000L
        /** #1174 (друга смуга) — the pinned instant the completion is stamped with. */
        const val FINISHED_AT = 1_800_000_000_000L
        const val BOOK_TOTAL_SECONDS = 3_600L
        const val AUDIO_FORMAT = "AUDIO"
        const val ABANDONED_STATE = "ABANDONED"
        const val IN_PROGRESS_STATE = "IN_PROGRESS"
        const val PLANNED_STATE = "PLANNED"
        const val FINISHED_STATE = "FINISHED"

        /** Three chapters, 1200 s each, summing exactly to the book total. */
        val CHAPTER_DURATIONS = listOf(1_200L, 1_200L, 1_200L)
    }
}
