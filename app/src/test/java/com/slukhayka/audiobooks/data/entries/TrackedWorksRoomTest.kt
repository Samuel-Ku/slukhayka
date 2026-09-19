package com.slukhayka.audiobooks.data.entries

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.merge.MergeKey
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
 * ADR-0053 / #854 (T1) — the tracked Work against **in-memory Room**: the
 * write path is exercised through the real SQL doors (the guarded card insert,
 * the Work upsert, the Library Entry with its origin), so the test proves the
 * external behaviour the milestone asks for, not a fake's bookkeeping.
 *
 * Covered: the card is visible in the library with honest unknowns, the
 * mergeKey dedup holds across a re-add and across a Work already imported,
 * the tombstone gate blocks a deleted Work, and the manual «Прослухано» flag
 * works with NO Edition row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrackedWorksRoomTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    private val trackedWorks = { TrackedWorks(dao) }

    private val mergeKey = MergeKey.keyFor(TITLE, AUTHOR)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun add(title: String = TITLE, author: String = AUTHOR): TrackedWorks.Result =
        trackedWorks().ensureTrackedWork(title, author, now = NOW)

    @Test
    fun `a tracked work lands a real library card with honest unknowns and no audio rows`() = runBlocking {
        val result = add()

        assertTrue(result is TrackedWorks.Result.Added)
        assertEquals(mergeKey, (result as TrackedWorks.Result.Added).work.bookId)

        // Visible in the ordinary library read (the card row + its Work join).
        val card = dao.getAudiobookById(mergeKey)!!
        assertEquals(TITLE, card.title)
        assertEquals(AUTHOR, card.author)
        assertEquals("the card is in the library read", mergeKey, card.mergeKey)

        // ADR-0014/0053: no invented duration, chapters or source.
        assertEquals(0L, card.totalDurationSeconds)
        assertEquals(0, card.totalChapters)
        assertEquals("no source URL is claimed", "", card.sourceUrl)
        assertTrue("no Source rows", dao.getSourcesForBookSync(mergeKey).isEmpty())
        assertNull("no Edition rows", dao.getEditionForWork(mergeKey))

        // ADR-0047: a manual add is the listener's explicit save, not triage.
        val entry = dao.libraryEntryById(mergeKey)!!
        assertEquals(mergeKey, entry.workId)
        assertEquals(LibraryEntryOrigin.EXPLICIT_SAVE.name, entry.origin)
    }

    @Test
    fun `the same mergeKey never forks a second card - even across normalisation`() = runBlocking {
        val first = add() as TrackedWorks.Result.Added
        val second = add(title = "  кобзар!  ", author = "Тарас Шевченко") as TrackedWorks.Result.AlreadyTracked

        assertEquals(first.work.bookId, second.work.bookId)
        assertEquals("one card", 1, dao.getAllAudiobooksOnce().size)
        assertEquals("one link", 1, dao.countLibraryEntries())
    }

    @Test
    fun `a Work already living in the library is not forked by a manual add`() = runBlocking {
        // The imported-style card: a source-derived id, the SAME Work identity.
        dao.insertCatalogBookIfNotTombstoned(
            id = "4read-1",
            title = TITLE,
            author = AUTHOR,
            narrator = "Диктор",
            description = "",
            coverDrawableRes = 0,
            coverImageUrl = null,
            genre = "",
            sourceUrl = "https://4read.org/1.html",
            isDownloaded = false,
            totalDurationSeconds = 3_600L,
            totalChapters = 5,
            rating = 0f,
            sourceTreeUri = null
        )
        dao.upsertWork(
            WorkEntity(id = mergeKey, mergeKey = mergeKey, title = TITLE, author = AUTHOR)
        )
        dao.upsertLibraryEntry(
            id = "4read-1",
            workId = mergeKey,
            isFavorite = false,
            createdAt = NOW,
            downloadProgress = 0f
        )

        val result = add()

        assertTrue(result is TrackedWorks.Result.AlreadyTracked)
        assertEquals(
            "the existing card is the one returned",
            "4read-1",
            (result as TrackedWorks.Result.AlreadyTracked).work.bookId
        )
        assertEquals("no second card", 1, dao.getAllAudiobooksOnce().size)
        assertNull("no tracked card was written", dao.getAudiobookById(mergeKey))
    }

    @Test
    fun `a tombstoned Work is refused and nothing is written`() = runBlocking {
        dao.insertTombstone(TombstoneEntity(bookId = mergeKey))

        val result = add()

        assertEquals(
            TrackedWorks.Result.Refused(TrackedWorkPolicy.REASON_TOMBSTONED),
            result
        )
        assertNull(dao.getAudiobookById(mergeKey))
        assertNull(dao.libraryEntryById(mergeKey))
        assertNull(dao.getWorkById(mergeKey))
    }

    @Test
    fun `a blank identity is refused`() = runBlocking {
        assertEquals(
            TrackedWorks.Result.Refused(TrackedWorkPolicy.REASON_NO_IDENTITY),
            trackedWorks().ensureTrackedWork("Кобзар", "   ", now = NOW)
        )
        assertEquals(0, dao.getAllAudiobooksOnce().size)
    }

    @Test
    fun `deleting a tracked work tombstones the Work and the door refuses to resurrect it`() = runBlocking {
        val added = add() as TrackedWorks.Result.Added

        LibraryEntries(dao, emptyList()).deleteBook(added.work.bookId)

        assertTrue("the tombstone carries the Work identity", dao.isBookTombstoned(mergeKey))
        assertNull(dao.getAudiobookById(mergeKey))
        assertEquals(
            TrackedWorks.Result.Refused(TrackedWorkPolicy.REASON_TOMBSTONED),
            add()
        )
    }

    @Test
    fun `the manual Прослухано flag is set and cleared with NO Edition row`() = runBlocking {
        val added = add() as TrackedWorks.Result.Added
        val listening = ListeningStateStore(dao)

        listening.setCompleted(added.work.bookId, true)

        assertTrue(dao.getPlaybackProgressSync(added.work.bookId)?.isCompleted == true)
        assertNull("the flag never invents an Edition", dao.getEditionForWork(added.work.bookId))

        listening.setCompleted(added.work.bookId, false)

        assertFalse(dao.getPlaybackProgressSync(added.work.bookId)?.isCompleted == true)
    }

    private companion object {
        const val TITLE = "Кобзар"
        const val AUTHOR = "Тарас Шевченко"
        const val NOW = 1_700_000_000_000L
    }
}
