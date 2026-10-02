package com.slukhayka.audiobooks.data.listening

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import kotlinx.coroutines.flow.first
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
 * #1101 — the repair pass for rows whose book is gone.
 *
 * The damage is older than the fix: nine v32→v42 migrations ran
 * `DELETE FROM audiobooks WHERE sourceUrl LIKE '%4read.org%'` without touching
 * `bookmarks`, `playback_progress` or `playback_events`, and none of those
 * tables has a foreign key, so the orphans are still on every device that
 * crossed those versions. The migration chain itself is covered by
 * `IntegratedPeopleMigrationTest` (which now walks a v26 database through the
 * 4read purge and onto the current version); this class covers the repair that
 * has to run for everyone who upgraded *before* the repair existed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class OrphanListeningStatePurgeTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

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

    private fun seedBook(id: String, sourceUrl: String) = runBlocking {
        dao.insertAudiobooks(
            listOf(
                com.slukhayka.audiobooks.data.db.AudiobookEntity(
                    id = id,
                    title = "Книга $id",
                    author = "Автор",
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    genre = "",
                    sourceUrl = sourceUrl
                )
            )
        )
    }

    private fun seedBookmark(bookId: String, editionId: String? = null, note: String = "") =
        runBlocking {
            dao.insertBookmark(
                com.slukhayka.audiobooks.data.db.BookmarkEntity(
                    bookId = bookId,
                    editionId = editionId,
                    chapterIndex = 0,
                    chapterTitle = "Розділ 1",
                    timestampSeconds = 10,
                    note = note
                )
            )
        }

    private fun seedProgress(bookId: String, editionId: String) = runBlocking {
        dao.savePlaybackProgress(
            com.slukhayka.audiobooks.data.db.PlaybackProgressEntity(
                editionId = editionId,
                bookId = bookId,
                currentChapterIndex = 0,
                currentPositionSeconds = 22
            )
        )
    }

    private fun seedEvent(bookId: String) = runBlocking {
        dao.insertPlaybackEvent(
            com.slukhayka.audiobooks.data.db.PlaybackEventEntity(
                bookId = bookId,
                kind = "RESUME",
                chapterIndex = 0,
                positionSeconds = 22
            )
        )
    }

    /**
     * The exact shape the migrations left: the book is gone, the bookmark and
     * the listening rows are not. This is #1081's dead tap, at the data layer.
     */
    @Test
    fun removesBookmarkAndListeningRowsWhoseBookIsGone() = runBlocking {
        seedBook("alive-1", "https://sound-books.net/alive")
        seedBookmark("alive-1", editionId = "ed-1", note = "жива")
        seedProgress("alive-1", "ed-1")

        seedBookmark("4read-orphan", editionId = "ed-dead", note = "сирота")
        seedProgress("4read-orphan", "ed-dead")
        seedEvent("4read-orphan")

        assertEquals(1, dao.countOrphanBookmarks())
        assertEquals(1, dao.countOrphanPlaybackProgress())
        assertEquals(1, dao.countOrphanPlaybackEvents())

        val removed = OrphanListeningStatePurge(dao).purgeOnce()
        assertEquals("кожен сирітський рядок прибирається рівно раз", 3, removed)

        assertEquals(0, dao.countOrphanBookmarks())
        assertEquals(0, dao.countOrphanPlaybackProgress())
        assertEquals(0, dao.countOrphanPlaybackEvents())

        // The living book keeps its bookmark exactly as it was, so the repair
        // can never be mistaken for a library-wide cleanup.
        val alive = dao.getAllBookmarks().first()
        assertEquals(1, alive.size)
        assertEquals("alive-1", alive.single().bookId)
        assertEquals("жива", alive.single().note)
    }

    /** Idempotent by contract: the second run finds nothing and removes nothing. */
    @Test
    fun secondRunRemovesNothing() = runBlocking {
        seedBookmark("orphan", note = "сирота")
        val purge = OrphanListeningStatePurge(dao)

        assertEquals(1, purge.purgeOnce())
        assertEquals("повторний прохід не має що прибирати", 0, purge.purgeOnce())
        assertEquals(0, purge.countOrphans())
    }

    /**
     * The pass repairs rows whose book is gone — it is not a licence to touch a
     * bookmark whose Edition is gone but whose book is alive. `ScamSourcePurge`
     * owns that case by Edition, and a live book's bookmark is never ours to
     * drop on a guess.
     */
    @Test
    fun keepsBookmarksOfLivingBooksEvenWithoutAnEdition() = runBlocking {
        seedBook("alive-2", "https://sound-books.net/alive-2")
        seedBookmark("alive-2", editionId = null, note = "без Edition")

        assertEquals(0, OrphanListeningStatePurge(dao).purgeOnce())
        assertTrue(
            "закладка живої книги не чіпається, навіть якщо Edition порожній",
            dao.getAllBookmarks().first().any { it.bookId == "alive-2" }
        )
    }

    /** A database whose books all resolve is left completely untouched. */
    @Test
    fun leavesAHealthyDatabaseUntouched() = runBlocking {
        seedBook("a", "https://sound-books.net/a")
        seedBook("b", "https://4read.org/b")
        seedBookmark("a")
        seedBookmark("b")
        seedProgress("b", "ed-b")

        assertEquals(0, OrphanListeningStatePurge(dao).purgeOnce())
        assertEquals(
            "здорова база лишається недоторканою",
            2,
            dao.getAllBookmarks().first().size
        )
        assertEquals(
            "жодного сирітського рядка в здоровій базі",
            0,
            dao.countOrphanBookmarks() + dao.countOrphanPlaybackProgress() + dao.countOrphanPlaybackEvents()
        )
    }
}
