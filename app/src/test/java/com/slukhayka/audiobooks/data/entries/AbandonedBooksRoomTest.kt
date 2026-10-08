package com.slukhayka.audiobooks.data.entries

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.ReadthroughMapping
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-52 US28 / #1174 — the write path of «покинути книгу» against **in-memory
 * Room**, so the action is proved through the real SQL doors: the pass is
 * UPSERTED under the deterministic `rt-audio-<entryId>` id (a repeat call can
 * never fork a second pass), the live position stays in Listening State, and
 * the cancel returns the book to the state its evidence proves.
 *
 * The two edges (a finished book, a book with no position) are refused here as
 * well — the UI hides the action for them, but the module is the door.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AbandonedBooksRoomTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var listening: ListeningStateStore
    private lateinit var abandonedBooks: AbandonedBooks

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        listening = ListeningStateStore(dao)
        abandonedBooks = AbandonedBooks(dao, listening)
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
                    totalDurationSeconds = 3_600L,
                    totalChapters = 3
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
                totalChapters = 3,
                totalDurationSeconds = 3_600L
            )
        )
        assertTrue(
            "an import writes no pass of its own — the action must create one",
            dao.readthroughsForEntry(bookId).isEmpty()
        )
    }

    /** A real listening position, written the way playback writes it. */
    private suspend fun listen(bookId: String = BOOK_ID, completed: Boolean = false) {
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = EDITION_ID,
                bookId = bookId,
                currentChapterIndex = 2,
                currentPositionSeconds = 120L,
                lastListenedAt = 1_700_000_000_000L,
                isCompleted = completed
            )
        )
    }

    @Test
    fun `abandon upserts ONE audio pass under the deterministic id`() = runBlocking {
        seedImportedBook()
        listen()

        val result = abandonedBooks.abandon(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(ReadingState.ABANDONED), result)
        val rows = dao.readthroughsForEntry(BOOK_ID)
        assertEquals("exactly one pass", 1, rows.size)
        val row = rows.single()
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
    fun `the live position stays in Listening State after the mark`() = runBlocking {
        seedImportedBook()
        listen()

        abandonedBooks.abandon(BOOK_ID)

        val progress = listening.getProgressSync(BOOK_ID)!!
        assertEquals(2, progress.currentChapterIndex)
        assertEquals(120L, progress.currentPositionSeconds)
    }

    @Test
    fun `a repeated abandon rewrites the same row instead of forking a pass`() = runBlocking {
        seedImportedBook()
        listen()

        abandonedBooks.abandon(BOOK_ID)
        val second = abandonedBooks.abandon(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(ReadingState.ABANDONED), second)
        assertEquals("still exactly one pass", 1, dao.readthroughsForEntry(BOOK_ID).size)
    }

    @Test
    fun `an existing pass keeps its own moment, units and journal`() = runBlocking {
        seedImportedBook()
        listen()
        val stored = ReadthroughPolicy.recordProgress(
            ReadthroughPolicy.start(
                id = AbandonedBooks.readthroughId(BOOK_ID),
                libraryEntryId = BOOK_ID,
                workId = WORK_ID,
                format = ReadingFormat.AUDIO,
                startedAt = 5L,
                editionId = EDITION_ID,
                value = 600
            )!!,
            at = 10L,
            value = 900
        )!!
        with(ReadthroughMapping) { dao.upsertReadthrough(stored.toEntity()) }

        abandonedBooks.abandon(BOOK_ID)

        val row = dao.readthroughsForEntry(BOOK_ID).single()
        assertEquals(ABANDONED_STATE, row.state)
        assertEquals("the pass keeps the moment it began", 5L, row.startedAt)
        assertEquals("and the units it already knew", 900, row.unitValue)
        assertEquals("the journal is history, never rewritten", 1, stored.journal.size)
        assertEquals(
            with(ReadthroughMapping) { stored.toEntity() }.journalJson,
            row.journalJson
        )
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
    fun `cancel returns the pass to IN_PROGRESS and leaves the position alone`() = runBlocking {
        seedImportedBook()
        listen()
        abandonedBooks.abandon(BOOK_ID)

        val result = abandonedBooks.cancel(BOOK_ID)

        assertEquals(AbandonedBooks.Result.Changed(ReadingState.IN_PROGRESS), result)
        val row = dao.readthroughsForEntry(BOOK_ID).single()
        assertEquals(IN_PROGRESS_STATE, row.state)
        assertEquals("still one pass, not two", 1, dao.readthroughsForEntry(BOOK_ID).size)
        assertEquals(120L, listening.getProgressSync(BOOK_ID)!!.currentPositionSeconds)
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

    private companion object {
        const val BOOK_ID = "entry-1"
        const val WORK_ID = "work-1"
        const val EDITION_ID = "edition-1"
        const val TITLE = "Острів Дума"
        const val AUTHOR = "Тарас Шевченко"
        const val ENTERED_AT = 1_700_000_000_000L
        const val AUDIO_FORMAT = "AUDIO"
        const val ABANDONED_STATE = "ABANDONED"
        const val IN_PROGRESS_STATE = "IN_PROGRESS"
    }
}
