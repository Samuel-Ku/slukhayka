package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.TombstoneEntity
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
import java.io.ByteArrayInputStream

/**
 * #612 Local Source T1 — a re-scan of a local folder mutates ONLY the exact
 * local Source of the matched Edition: a mixed-Source Edition's direct tracks,
 * Chapter ids/indices and Listening State survive untouched; a structural
 * change without a proven mapping is rejected explicitly with zero Room
 * writes; a local-only Edition appends new Chapters at the end; a tombstoned
 * book is never resurrected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalSourceRescanGuardTest {

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

    private fun imports() = LibraryImport(dao, context, emptyList())

    private fun entry(name: String, byte: Int) =
        LocalAudioEntry(name, "Кобзар") { ByteArrayInputStream(ByteArray(16) { byte.toByte() }) }

    private suspend fun importTwoChapters(): String {
        imports().importAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2)),
            sourceTreeUri = "content://tree/books"
        )
        return dao.getAllAudiobooks().first().first { it.title == "Кобзар" }.id
    }

    @Test
    fun `a structural change on a mixed-Source Edition is rejected with zero writes`() = runBlocking {
        val bookId = importTwoChapters()
        val edition = dao.getEditionForWork(bookId)!!
        val localSource = dao.getSourcesForBookSync(bookId).first { it.type == "local" }
        // A real direct Source of the same Edition — the mixed-Source case.
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = "soundbooks-${edition.id}",
                    bookId = bookId,
                    editionId = edition.id,
                    type = "soundbooks",
                    url = "https://sound-books.net/kobzar"
                )
            )
        )
        // Its track index collides with the local track 0 — an index alone is
        // never an identity.
        dao.insertTracks(
            listOf(
                SourceTrackEntity(
                    id = "soundbooks-${edition.id}_tr_1",
                    sourceId = "soundbooks-${edition.id}",
                    trackIndex = 0,
                    url = "https://arch.sound-books.net/01.mp3",
                    contentHash = "direct-hash"
                )
            )
        )
        dao.savePlaybackProgress(
            PlaybackProgressEntity(editionId = edition.id, bookId = bookId, currentPositionSeconds = 42L)
        )
        dao.insertBookmark(
            BookmarkEntity(bookId = bookId, editionId = edition.id, chapterIndex = 0, chapterTitle = "01.mp3", timestampSeconds = 10L, note = "")
        )
        dao.updateBookMetadata(bookId, author = "Тарас Шевченко", narrator = null, genre = null, rating = 4.5f)
        val chaptersBefore = dao.getChaptersListForBook(bookId).map { it.id to it.chapterIndex }
        val localTracksBefore = dao.getTracksForSourceSync(localSource.id).map { it.id to it.trackIndex }

        // The user drops a new file into the folder — an unproven topology change.
        val report = imports().rescanAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2), entry("03.mp3", 3)),
            treeUri = "content://tree/books"
        )

        assertTrue("the explicit rejection result", report.structuralChangeRejected)
        assertEquals(0, report.newChapters)
        assertEquals("rejected files are not miscounted as duplicates", 0, report.duplicateFiles)
        assertEquals(chaptersBefore, dao.getChaptersListForBook(bookId).map { it.id to it.chapterIndex })
        assertEquals(localTracksBefore, dao.getTracksForSourceSync(localSource.id).map { it.id to it.trackIndex })
        assertEquals(
            "direct tracks are never touched",
            listOf("direct-hash"),
            dao.getTracksForSourceSync("soundbooks-${edition.id}").map { it.contentHash }
        )
        assertEquals(42L, dao.getPlaybackProgressSyncByEdition(edition.id)?.currentPositionSeconds)
        assertEquals(1, dao.getBookmarksForBookSync(bookId).size)
        val metadataAfter = dao.getAudiobookById(bookId)!!
        assertEquals("Тарас Шевченко", metadataAfter.author)
        assertEquals(4.5f, metadataAfter.rating)

        // The unchanged folder keeps reporting itself unchanged: the direct
        // Source stays complete (its track index 0 collides with the local one).
        val steady = imports().rescanAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2)),
            treeUri = "content://tree/books"
        )
        assertFalse(steady.structuralChangeRejected)
        assertEquals(0, steady.missingFiles)
        assertEquals(0, steady.movedFiles)
        assertEquals(
            listOf("direct-hash"),
            dao.getTracksForSourceSync("soundbooks-${edition.id}").map { it.contentHash }
        )
    }

    @Test
    fun `a local-only Edition appends new chapters at the end without moving stored anchors`() = runBlocking {
        val bookId = importTwoChapters()
        val edition = dao.getEditionForWork(bookId)!!
        val localSource = dao.getSourcesForBookSync(bookId).first { it.type == "local" }
        dao.savePlaybackProgress(
            PlaybackProgressEntity(editionId = edition.id, bookId = bookId, currentPositionSeconds = 42L)
        )
        dao.insertBookmark(
            BookmarkEntity(bookId = bookId, editionId = edition.id, chapterIndex = 1, chapterTitle = "02.mp3", timestampSeconds = 7L, note = "")
        )
        val chaptersBefore = dao.getChaptersListForBook(bookId).map { it.id to it.chapterIndex }

        // "00-intro.mp3" sorts FIRST naturally — the append must still place
        // it after the stored chapters and keep their ids/indices.
        val report = imports().rescanAudioEntries(
            listOf(entry("00-intro.mp3", 3), entry("01.mp3", 1), entry("02.mp3", 2)),
            treeUri = "content://tree/books"
        )

        assertFalse(report.structuralChangeRejected)
        assertEquals(1, report.newChapters)
        val chaptersAfter = dao.getChaptersListForBook(bookId)
        assertEquals(chaptersBefore, chaptersAfter.take(2).map { it.id to it.chapterIndex })
        assertEquals(2, chaptersAfter.last().chapterIndex)
        assertEquals("00-intro", chaptersAfter.last().title)
        assertEquals(42L, dao.getPlaybackProgressSyncByEdition(edition.id)?.currentPositionSeconds)
        assertEquals(setOf(1), dao.getBookmarksForBookSync(bookId).map { it.chapterIndex }.toSet())
        val localTracks = dao.getTracksForSourceSync(localSource.id).sortedBy { it.trackIndex }
        assertEquals(listOf(0, 1, 2), localTracks.map { it.trackIndex })
        assertEquals(3, dao.getAudiobookById(bookId)?.totalChapters)
        assertEquals("the Edition list stays honest", 3, dao.getEditionForWork(bookId)?.totalChapters)
    }

    @Test
    fun `a tombstoned book is never resurrected nor mutated by a rescan`() = runBlocking {
        val bookId = importTwoChapters()
        dao.insertTombstone(TombstoneEntity(bookId = bookId))
        val chaptersBefore = dao.getChaptersListForBook(bookId).map { it.id }

        val report = imports().rescanAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2), entry("03.mp3", 3)),
            treeUri = "content://tree/books"
        )

        assertEquals(0, report.newChapters)
        assertEquals(0, report.newBooks)
        assertTrue("the tombstone stays", dao.isBookTombstoned(bookId))
        assertEquals(chaptersBefore, dao.getChaptersListForBook(bookId).map { it.id })
        assertEquals(1, dao.getAllAudiobooks().first().size)
    }
}
