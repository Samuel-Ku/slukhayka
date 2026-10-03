package com.slukhayka.audiobooks.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1049 — reordering an ALREADY ADDED book.
 *
 * The listener's report is about a YouTube playlist that arrived back-to-front.
 * Slice A reorders it in the preview; this is the case where the book is
 * already in the library.
 *
 * The point of these tests is NOT that the indices change — that is the easy
 * half. It is that the things a reorder must NOT touch survive:
 *  - the listener's playback position, because they only MOVED a chapter;
 *  - their bookmarks;
 *  - and the chapter/track pairing, because ADR-0007 pairs the two by index,
 *    so a chapter moved without its track plays the wrong audio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReorderAddedBookTest {

    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    private val bookId = "book-1"
    private val editionId = "ed-1"
    private val sourceId = "src-1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AudiobookDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedThreeChapters() {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = "Книга",
                    author = "Автор",
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    genre = "",
                    sourceUrl = ""
                )
            )
        )
        dao.insertChapters(
            (0..2).map { i ->
                ChapterEntity(
                    id = "ch-$i",
                    bookId = bookId,
                    chapterIndex = i,
                    title = "Розділ ${i + 1}",
                    durationSeconds = 100,
                    editionId = editionId
                )
            }
        )
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = sourceId,
                    bookId = bookId,
                    editionId = editionId,
                    type = "local",
                    url = "/tmp/book"
                )
            )
        )
        dao.insertTracks(
            (0..2).map { i ->
                SourceTrackEntity(
                    id = "tr-$i",
                    sourceId = sourceId,
                    trackIndex = i,
                    url = "/tmp/$i.mp3",
                    localFilePath = "/tmp/$i.mp3"
                )
            }
        )
    }

    @Test
    fun `reordering swaps the indices and the chapter keeps its track`() = runBlocking {
        seedThreeChapters()

        // Put chapter 3 first — exactly the playlist complaint, on a saved book.
        dao.reorderChaptersByIndex(
            chapterIdsInOrder = listOf("ch-2", "ch-0", "ch-1"),
            trackIdsInOrder = listOf("tr-2", "tr-0", "tr-1")
        )

        val chapters = dao.getChaptersListForBook(bookId).associateBy { it.id }
        assertEquals(0, chapters.getValue("ch-2").chapterIndex)
        assertEquals(1, chapters.getValue("ch-0").chapterIndex)
        assertEquals(2, chapters.getValue("ch-1").chapterIndex)

        // ADR-0007 — the pairing travelled with the chapter, so the audio that
        // plays for the first row is the audio the listener moved there.
        // Assert the INDICES, not a sorted list: sorting by a column that a
        // bug could duplicate would let a tie pass for a correct order. The
        // index IS the pairing ADR-0007 relies on, so it is what we check.
        val byId = dao.getTracksForBookSync(bookId).associateBy { it.id }
        assertEquals("жоден трек не загубився", 3, byId.size)
        assertEquals(0, byId.getValue("tr-2").trackIndex)
        assertEquals(1, byId.getValue("tr-0").trackIndex)
        assertEquals(2, byId.getValue("tr-1").trackIndex)
    }

    @Test
    fun `reordering keeps the listener's position and bookmarks`() = runBlocking {
        seedThreeChapters()
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = editionId,
                bookId = bookId,
                currentChapterIndex = 1,
                currentPositionSeconds = 42L
            )
        )
        dao.insertBookmark(
            BookmarkEntity(
                bookId = bookId,
                editionId = editionId,
                chapterIndex = 1,
                chapterTitle = "Розділ 2",
                timestampSeconds = 42L,
                note = "тут"
            )
        )

        dao.reorderChaptersByIndex(
            chapterIdsInOrder = listOf("ch-2", "ch-0", "ch-1"),
            trackIdsInOrder = listOf("tr-2", "tr-0", "tr-1")
        )

        // The WHOLE point: a move is not a repair. If this test ever fails,
        // the listener lost the place they were listening from.
        val progress = dao.getPlaybackProgressSyncByEdition(editionId)
        assertEquals(
            "позиція мусить пережити перестановку",
            42L, progress?.currentPositionSeconds
        )
        assertEquals(1, dao.getBookmarksForBookSync(bookId).size)
    }

    @Test
    fun `a single step move swaps only its two neighbours`() = runBlocking {
        seedThreeChapters()

        // Move the THIRD chapter one step up: [0, 2, 1].
        dao.reorderChaptersByIndex(
            chapterIdsInOrder = listOf("ch-0", "ch-2", "ch-1"),
            trackIdsInOrder = listOf("tr-0", "tr-2", "tr-1")
        )

        val chapters = dao.getChaptersListForBook(bookId).sortedBy { it.chapterIndex }
        assertEquals(listOf("ch-0", "ch-2", "ch-1"), chapters.map { it.id })
    }
}
