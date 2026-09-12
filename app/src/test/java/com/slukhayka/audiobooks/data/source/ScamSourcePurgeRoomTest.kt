package com.slukhayka.audiobooks.data.source

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room seam for the one-time scam purge (4read's 52-second artefact): a
 * scam-only rendition leaves the DB whole — files, tracks, sources,
 * chapters, bookmarks, Listening State, facet and Edition — while the Work
 * and the library CARD stay with honest zeroed totals and a blank page URL.
 * A rendition with a real source loses only the scam rows. Idempotent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScamSourcePurgeRoomTest {

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

    private fun scamBook(id: String) = AudiobookEntity(
        id = id,
        title = "Передісторія",
        author = "Джоан Роулінг",
        narrator = "Mike Juice",
        description = "",
        coverDrawableRes = 0,
        genre = "Фентезі",
        sourceUrl = "https://4read.org/book.html",
        totalDurationSeconds = 52L,
        totalChapters = 1
    )

    private suspend fun seedScamRendition(bookId: String, editionId: String, sourceId: String, filePath: String) {
        dao.insertAudiobooks(listOf(scamBook(bookId)))
        dao.insertEdition(
            EditionEntity(
                id = editionId,
                workId = bookId,
                narrator = "Mike Juice",
                totalChapters = 1,
                totalDurationSeconds = 52L
            )
        )
        dao.insertChapters(
            listOf(
                ChapterEntity(
                    id = "$editionId-ch1",
                    bookId = bookId,
                    chapterIndex = 0,
                    title = "Глава 1",
                    durationSeconds = 52L,
                    editionId = editionId
                )
            )
        )
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = sourceId,
                    bookId = bookId,
                    editionId = editionId,
                    type = "4read",
                    url = "https://4read.org/book.html"
                )
            )
        )
        dao.insertTracks(
            listOf(
                SourceTrackEntity(
                    id = "${sourceId}_tr_1",
                    sourceId = sourceId,
                    trackIndex = 0,
                    url = "https://cdn.4read/1.mp3",
                    localFilePath = filePath,
                    isDownloaded = true
                )
            )
        )
        dao.savePlaybackProgress(
            PlaybackProgressEntity(editionId = editionId, bookId = bookId, currentPositionSeconds = 26L)
        )
        dao.insertBookmark(
            BookmarkEntity(
                bookId = bookId,
                editionId = editionId,
                chapterIndex = 0,
                chapterTitle = "Глава 1",
                timestampSeconds = 10L,
                note = ""
            )
        )
        dao.mergeEditionFacet(
            editionId = editionId,
            workId = bookId,
            narratorId = null,
            language = "uk",
            durationSeconds = 52L,
            durationBucketId = "short",
            chapterCount = 1,
            isAbridged = null,
            availabilityAvailable = null,
            availabilityObservedAtMillis = null,
            availabilityTtlSeconds = null,
            updatedAt = 1L
        )
    }

    @Test
    fun `a scam-only rendition is purged whole and the card stays honest`() = runBlocking {
        val deletedFiles = mutableListOf<String>()
        seedScamRendition("b1", "e1", "4read-e1", "/tmp/scam-1.mp3")
        dao.upsertWork(
            WorkEntity(
                id = "w1",
                mergeKey = "передісторія|джоан роулінг",
                title = "Передісторія",
                author = "Джоан Роулінг"
            )
        )
        dao.upsertWorkSource(
            WorkSourceEntity(id = "ws1", workId = "w1", sourceId = "4read", sourceUrl = "https://4read.org/book.html")
        )

        val purge = ScamSourcePurge(dao, deleteFile = { deletedFiles += it })
        assertEquals(1, purge.purgeOnce())

        assertTrue(dao.getSourcesForBookSync("b1").isEmpty())
        assertTrue(dao.getTracksForSourceSync("4read-e1").isEmpty())
        assertTrue(dao.getChaptersListForBook("b1").isEmpty())
        assertTrue(dao.getBookmarksForBookSync("b1").isEmpty())
        assertNull(dao.getPlaybackProgressSyncByEdition("e1"))
        assertNull(dao.getEditionById("e1"))
        assertNull(dao.getEditionFacet("e1"))
        assertEquals(listOf("/tmp/scam-1.mp3"), deletedFiles)

        // The Work and the card survive — honest unavailable, no fake length.
        val card = dao.getAudiobookById("b1")!!.toAudiobookEntity()
        assertEquals(0L, card.totalDurationSeconds)
        assertEquals(0, card.totalChapters)
        assertEquals("", card.sourceUrl)

        assertEquals("a second run is a no-op", 0, purge.purgeOnce())
    }

    @Test
    fun `an edition with a real source loses only the scam rows`() = runBlocking {
        val deletedFiles = mutableListOf<String>()
        seedScamRendition("b2", "e2", "4read-e2", "/tmp/scam-2.mp3")
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = "soundbooks-e2",
                    bookId = "b2",
                    editionId = "e2",
                    type = "soundbooks",
                    url = "https://sound-books.net/x"
                )
            )
        )

        val purge = ScamSourcePurge(dao, deleteFile = { deletedFiles += it })
        assertEquals(1, purge.purgeOnce())

        assertEquals(listOf("soundbooks-e2"), dao.getSourcesForEditionSync("e2").map { it.id })
        assertNotNull(dao.getEditionById("e2"))
        assertEquals(1, dao.getChaptersListForBook("b2").size)
        assertEquals(listOf("/tmp/scam-2.mp3"), deletedFiles)
        // The real source keeps the card's stats untouched.
        assertEquals(52L, dao.getAudiobookById("b2")!!.totalDurationSeconds)
        assertEquals(0, purge.purgeOnce())
    }

    @Test
    fun `no scam rows is a no-op`() = runBlocking {
        dao.insertAudiobooks(listOf(scamBook("b3")))
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = "soundbooks-b3",
                    bookId = "b3",
                    editionId = "e3",
                    type = "soundbooks",
                    url = "https://sound-books.net/x"
                )
            )
        )

        assertEquals(0, ScamSourcePurge(dao).purgeOnce())
        assertEquals(listOf("soundbooks-b3"), dao.getSourcesForBookSync("b3").map { it.id })
    }
}
