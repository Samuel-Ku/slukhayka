package com.slukhayka.audiobooks.data.downloads

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #397 — the partial-offline contract at the module seam: removing ONE
 * chapter's copy keeps the rest on disk, drops the book out of the full
 * "downloaded" state, and the aggregate reports the honest N/M.
 */
class OfflineDownloadsChapterRemovalTest {

    private fun downloadsWith(dao: FakeAudiobookDao): OfflineDownloads {
        val import = LibraryImport(dao, context = null, sourceAdapters = emptyList())
        val catalog = SourceCatalog(dao, emptyList(), import)
        return OfflineDownloads(dao, context = null, catalog)
    }

    private suspend fun seed(dao: FakeAudiobookDao, bookId: String) {
        dao.insertSources(
            listOf(SourceEntity(id = "s-1", bookId = bookId, editionId = "e-1", type = "fake", url = ""))
        )
        dao.insertTracks(
            listOf(
                SourceTrackEntity("tr-0", "s-1", 0, "https://fixtures.invalid/0.mp3", localFilePath = "/files/0.mp3", isDownloaded = true),
                SourceTrackEntity("tr-1", "s-1", 1, "https://fixtures.invalid/1.mp3", localFilePath = "/files/1.mp3", isDownloaded = true),
                SourceTrackEntity("tr-2", "s-1", 2, "https://fixtures.invalid/2.mp3", localFilePath = "/files/2.mp3", isDownloaded = true)
            )
        )
    }

    @Test
    fun `removing one chapter copy keeps the others and leaves the book partial`() = runBlocking {
        val book = TestDataFactory.dataBooks().first().copy(isDownloaded = true)
        val dao = FakeAudiobookDao(books = listOf(book))
        seed(dao, book.id)

        downloadsWith(dao).removeChapterDownload(book.id, 1)

        val tracks = dao.getTracksForBookSync(book.id)
        val removed = tracks.first { it.trackIndex == 1 }
        assertFalse(removed.isDownloaded)
        assertNull(removed.localFilePath)
        assertTrue(tracks.first { it.trackIndex == 0 }.isDownloaded)
        assertTrue(tracks.first { it.trackIndex == 2 }.isDownloaded)
        // Partial → the book is no longer fully offline.
        assertFalse(dao.getAudiobookById(book.id)?.isDownloaded ?: true)
    }

    @Test
    fun `the aggregate reports downloaded out of total tracks`() = runBlocking {
        val book = TestDataFactory.dataBooks().first().copy(isDownloaded = true)
        val dao = FakeAudiobookDao(books = listOf(book))
        seed(dao, book.id)

        downloadsWith(dao).removeChapterDownload(book.id, 1)

        val count = dao.observeBookDownloadCounts().first().first { it.bookId == book.id }
        assertEquals(3, count.total)
        assertEquals(2, count.downloaded)
    }

    @Test
    fun `removing an already-absent chapter copy changes nothing`() = runBlocking {
        val book = TestDataFactory.dataBooks().first().copy(isDownloaded = true)
        val dao = FakeAudiobookDao(books = listOf(book))
        seed(dao, book.id)

        downloadsWith(dao).removeChapterDownload(book.id, 9)

        assertEquals(3, dao.getTracksForBookSync(book.id).count { it.isDownloaded })
    }
}
