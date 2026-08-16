package com.slukhayka.audiobooks.data.downloads

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-27 (#184) BUG-001 — the destructive «clear all downloads» path, pinned
 * at the module seam: clearing the audio cache resets every book's download
 * flag and every track's local copy. The confirmation lives in the UI
 * (ClearCacheConfirmDialog); this test pins what the action itself does.
 */
class OfflineDownloadsTest {

    private fun downloadsWith(dao: FakeAudiobookDao): OfflineDownloads {
        val import = LibraryImport(dao, context = null, sourceAdapters = emptyList())
        val catalog = SourceCatalog(dao, emptyList(), import)
        return OfflineDownloads(dao, context = null, catalog)
    }

    @Test
    fun `clearAllAudioCache resets every book and track download state`() = runBlocking {
        val books = TestDataFactory.dataBooks().map { it.copy(isDownloaded = true) }
        val dao = FakeAudiobookDao(books = books)
        dao.insertTracks(
            listOf(
                SourceTrackEntity("tr-1", "s-1", 0, "https://fixtures.invalid/1.mp3", localFilePath = "/files/audiobooks/1.mp3", isDownloaded = true),
                SourceTrackEntity("tr-2", "s-2", 0, "https://fixtures.invalid/2.mp3", localFilePath = "/files/audiobooks/2.mp3", isDownloaded = true)
            )
        )

        downloadsWith(dao).clearAllAudioCache()

        assertEquals(0, dao.getAllAudiobooksOnce().count { it.isDownloaded })
        assertTrue(dao.savedTracks.all { !it.isDownloaded && it.localFilePath == null })
    }
}
