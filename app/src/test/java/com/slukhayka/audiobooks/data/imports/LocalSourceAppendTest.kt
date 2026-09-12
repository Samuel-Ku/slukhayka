package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
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
 * #615 Local Source T2 — new files join a local-only Edition at the END:
 * stored Chapter ids/indices, Listening State, bookmarks and known durations
 * never move, even when a new filename would naturally sort earlier. A
 * finished Edition reopens while its position is preserved; same-name/
 * different-bytes files never overwrite a stored Chapter, and a content
 * duplicate is never added twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalSourceAppendTest {

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

    private fun entry(name: String, byte: Int, folder: String = "Кобзар") =
        LocalAudioEntry(name, folder) { ByteArrayInputStream(ByteArray(16) { byte.toByte() }) }

    private suspend fun importKobzar(vararg files: Pair<String, Int>): String {
        imports().importAudioEntries(
            files.map { entry(it.first, it.second) },
            sourceTreeUri = TREE
        )
        return dao.getAllAudiobooks().first().first { it.title == "Кобзар" }.id
    }

    private suspend fun chapters(bookId: String) = dao.getChaptersListForBook(bookId)

    @Test
    fun `new chapters append last even when their filename sorts earlier`() = runBlocking {
        val bookId = importKobzar("01.mp3" to 1, "02.mp3" to 2)
        val before = chapters(bookId).map { Triple(it.id, it.chapterIndex, it.title) }

        val report = imports().rescanAudioEntries(
            listOf(
                entry("00.mp3", 9),
                entry("01.mp3", 1),
                entry("02.mp3", 2),
                entry("03.mp3", 3)
            ),
            treeUri = TREE
        )

        assertEquals(2, report.newChapters)
        val after = chapters(bookId)
        // Stored anchors are byte-for-byte the same rows, in the same slots.
        assertEquals(before, after.take(before.size).map { Triple(it.id, it.chapterIndex, it.title) })
        // The earlier-sorting file did NOT jump the queue.
        assertEquals(listOf(2, 3), after.drop(before.size).map { it.chapterIndex })
        assertEquals(listOf("00", "03"), after.drop(before.size).map { it.title })
        assertEquals(4, dao.getAudiobookById(bookId)?.totalChapters)
    }

    @Test
    fun `appending to a completed edition clears completion and keeps the position`() = runBlocking {
        val bookId = importKobzar("01.mp3" to 1, "02.mp3" to 2)
        val edition = dao.getEditionForWork(bookId)!!
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = edition.id,
                bookId = bookId,
                currentChapterIndex = 1,
                currentPositionSeconds = 42L,
                isCompleted = true
            )
        )

        imports().rescanAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2), entry("03.mp3", 3)),
            treeUri = TREE
        )

        val progress = dao.getPlaybackProgressSyncByEdition(edition.id)!!
        assertFalse("a reopened Edition is no longer finished", progress.isCompleted)
        assertEquals("the listener's position is preserved", 42L, progress.currentPositionSeconds)
        assertEquals(1, progress.currentChapterIndex)
    }

    @Test
    fun `an unknown new chapter never zeroes the known book duration`() = runBlocking {
        val bookId = importKobzar("01.mp3" to 1, "02.mp3" to 2)
        dao.updateBookStats(bookId, totalChapters = 2, totalDurationSeconds = 7200L)

        imports().rescanAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2), entry("03.mp3", 3)),
            treeUri = TREE
        )

        assertEquals(7200L, dao.getAudiobookById(bookId)?.totalDurationSeconds)
        assertEquals(3, dao.getAudiobookById(bookId)?.totalChapters)
    }

    @Test
    fun `same filename with other bytes never overwrites the stored chapter`() = runBlocking {
        val bookId = importKobzar("01.mp3" to 1, "02.mp3" to 2)
        val localSource = dao.getSourcesForBookSync(bookId).first { it.type == "local" }
        val storedFirst = chapters(bookId).first()
        val storedFirstTrackHash = dao.getTracksForSourceSync(localSource.id)
            .first { it.trackIndex == storedFirst.chapterIndex }.contentHash

        val report = imports().rescanAudioEntries(
            listOf(entry("01.mp3", 9), entry("02.mp3", 2)),
            treeUri = TREE
        )

        assertEquals(1, report.newChapters)
        assertEquals("the vanished old bytes are reported, not deleted", 1, report.missingFiles)
        // The stored Chapter row itself is untouched (same id, slot, title).
        val after = chapters(bookId)
        assertEquals(storedFirst.id, after.first().id)
        assertEquals(storedFirst.chapterIndex, after.first().chapterIndex)
        assertEquals("01", after.first().title)
        // The old private copy survived; the new bytes got their own Chapter.
        val trackHashes = dao.getTracksForSourceSync(localSource.id).mapNotNull { it.contentHash }
        assertTrue(trackHashes.contains(storedFirstTrackHash))
        assertEquals(3, after.size)
        assertEquals(2, after.last().chapterIndex)
    }

    @Test
    fun `an identical rescan adds nothing`() = runBlocking {
        val bookId = importKobzar("01.mp3" to 1, "02.mp3" to 2)

        val report = imports().rescanAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2)),
            treeUri = TREE
        )

        assertEquals(0, report.newChapters)
        assertEquals(0, report.duplicateFiles)
        assertEquals(0, report.missingFiles)
        assertEquals(2, chapters(bookId).size)
    }

    @Test
    fun `bytes already owned by another book are never appended twice`() = runBlocking {
        val kobzarId = importKobzar("01.mp3" to 1, "02.mp3" to 2)
        // Another book owns the bytes the user now drops into Кобзар.
        imports().importAudioEntries(
            listOf(entry("shared.mp3", 7, folder = "Інше")),
            sourceTreeUri = "content://tree/other"
        )

        val report = imports().rescanAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2), entry("03.mp3", 7)),
            treeUri = TREE
        )

        assertEquals(0, report.newChapters)
        assertEquals(1, report.duplicateFiles)
        assertEquals(2, chapters(kobzarId).size)
    }

    @Test
    fun `the first import still sorts files naturally before chapters exist`() = runBlocking {
        // The scanner hands over files in arbitrary order; the FIRST import
        // establishes the natural chapter order.
        val bookId = importKobzar("02.mp3" to 2, "01.mp3" to 1, "10.mp3" to 10)

        assertEquals(listOf("01", "02", "10"), chapters(bookId).map { it.title })
    }

    companion object {
        private const val TREE = "content://tree/books"
    }
}
