package com.slukhayka.audiobooks.data.downloads

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.DownloadState
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Spec-27 (#184) BUG-001 — the destructive «clear all downloads» path, pinned
 * at the module seam.
 *
 * #1163 — and pinned to its REAL scope. The clear destroys the SOURCE downloads
 * (files + state); a local import's private copy is not a download: it is the
 * only copy of a book the listener brought into the library (ADR-0057), so its
 * `localFilePath` and its `isDownloaded` flag must survive. The same ticket's
 * other half is the accounting: the app's bytes are measured from the files in
 * both of its own directories — never estimated, and never the listener's
 * files in an external folder.
 */
class OfflineDownloadsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun downloadsWith(dao: FakeAudiobookDao): OfflineDownloads {
        val import = LibraryImport(dao, context = null, sourceAdapters = emptyList())
        val catalog = SourceCatalog(dao, emptyList(), import)
        return OfflineDownloads(
            dao,
            context = null,
            sourceCatalog = catalog,
            filesDirOverride = tempFolder.root
        )
    }

    private fun appDir(name: String): File = File(tempFolder.root, name).also { it.mkdirs() }

    private fun file(dir: File, name: String, bytes: Int): File =
        File(dir, name).also { it.writeBytes(ByteArray(bytes) { index -> (index % 97).toByte() }) }

    private fun book(id: String, sourceUrl: String, isDownloaded: Boolean) = AudiobookEntity(
        id = id,
        title = "Книга $id",
        author = "Автор",
        narrator = "Читець",
        description = "",
        coverDrawableRes = 0,
        genre = "Фікстура",
        sourceUrl = sourceUrl,
        isDownloaded = isDownloaded
    )

    private fun source(id: String, bookId: String, type: String) = SourceEntity(
        id = id,
        bookId = bookId,
        type = type,
        url = if (type == "local") "" else "https://fixtures.invalid/$id"
    )

    @Test
    fun `clearAllAudioCache resets every source download state`() = runBlocking {
        val dao = FakeAudiobookDao(books = listOf(book("src", "https://fixtures.invalid/src", isDownloaded = true)))
        dao.insertSources(listOf(source("s-src", "src", "4read")))
        dao.insertTracks(
            listOf(
                SourceTrackEntity(
                    "tr-1", "s-src", 0, "https://fixtures.invalid/1.mp3",
                    localFilePath = "/files/audiobooks/1.mp3", isDownloaded = true
                )
            )
        )

        downloadsWith(dao).clearAllAudioCache()

        assertEquals(0, dao.getAllAudiobooksOnce().count { it.isDownloaded })
        assertTrue(dao.savedTracks.all { !it.isDownloaded && it.localFilePath == null })
    }

    @Test
    fun `clearAllAudioCache keeps a local import's copy and its on device state`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(
                book("src", "https://fixtures.invalid/src", isDownloaded = true),
                book("loc", "", isDownloaded = true)
            )
        )
        dao.insertSources(
            listOf(
                source("s-src", "src", "4read"),
                source("s-loc", "loc", "local")
            )
        )
        val audioDir = appDir(OfflineDownloads.OFFLINE_AUDIO_DIR)
        val localDir = appDir(LibraryImport.LOCAL_AUDIO_DIR)
        val downloaded = file(audioDir, "1.mp3", 64)
        val privateCopy = file(localDir, "loc-1.mp3", 48)
        dao.insertTracks(
            listOf(
                SourceTrackEntity(
                    "tr-src", "s-src", 0, "https://fixtures.invalid/1.mp3",
                    localFilePath = downloaded.absolutePath, isDownloaded = true
                ),
                SourceTrackEntity(
                    "tr-loc", "s-loc", 0, privateCopy.absolutePath,
                    localFilePath = privateCopy.absolutePath, isDownloaded = true
                )
            )
        )

        downloadsWith(dao).clearAllAudioCache()

        // The download is gone — file, flag and state.
        assertFalse(downloaded.exists())
        val sourceBook = dao.getAudiobookById("src")!!
        assertFalse(sourceBook.isDownloaded)
        assertEquals(DownloadState.IDLE, sourceBook.downloadState)
        val sourceTrack = dao.savedTracks.single { it.id == "tr-src" }
        assertFalse(sourceTrack.isDownloaded)
        assertEquals(null, sourceTrack.localFilePath)

        // The listener's own copy is not the app's download: untouched.
        assertTrue(privateCopy.exists())
        assertTrue(dao.getAudiobookById("loc")!!.isDownloaded)
        val localTrack = dao.savedTracks.single { it.id == "tr-loc" }
        assertTrue(localTrack.isDownloaded)
        assertEquals(privateCopy.absolutePath, localTrack.localFilePath)
    }

    @Test
    fun `getAppStorageUsage counts the actual files of both app directories`() = runBlocking {
        val dao = FakeAudiobookDao()
        val audioDir = appDir(OfflineDownloads.OFFLINE_AUDIO_DIR)
        val localDir = appDir(LibraryImport.LOCAL_AUDIO_DIR)
        file(audioDir, "1.mp3", 100)
        file(File(audioDir, "sub").also { it.mkdirs() }, "2.mp3", 50)
        file(localDir, "loc-1.mp3", 70)
        // Someone else's bytes: a file the listener keeps OUTSIDE the app dirs.
        file(File(tempFolder.root, "external").also { it.mkdirs() }, "own.mp3", 500)

        val downloads = downloadsWith(dao)

        assertEquals(150L, downloads.getAudioCacheSizeBytes())
        assertEquals(70L, downloads.getLocalCopiesSizeBytes())
        val usage = downloads.getAppStorageUsage()
        assertEquals(AppStorageUsage(downloadedBytes = 150L, localCopyBytes = 70L), usage)
        assertEquals(220L, usage.totalBytes)
    }

    @Test
    fun `bookIdsOnDevice follows the readable copy, not the historical flag`() = runBlocking {
        val dao = FakeAudiobookDao(
            books = listOf(
                book("here", "https://fixtures.invalid/here", isDownloaded = true),
                // The flag says downloaded; the file behind it is gone.
                book("gone", "https://fixtures.invalid/gone", isDownloaded = true),
                // A zero-byte stub is not a copy anyone can listen to.
                book("stub", "https://fixtures.invalid/stub", isDownloaded = true),
                // 3 of 20 chapters is not a book you have.
                book("partial", "https://fixtures.invalid/partial", isDownloaded = true),
                // Never downloaded, but the copy IS there: it is on the device.
                book("unflagged", "https://fixtures.invalid/unflagged", isDownloaded = false),
                book("loc", "", isDownloaded = true)
            )
        )
        dao.insertSources(
            listOf(
                source("s-here", "here", "4read"),
                source("s-gone", "gone", "4read"),
                source("s-stub", "stub", "4read"),
                source("s-partial", "partial", "4read"),
                source("s-unflagged", "unflagged", "4read"),
                source("s-loc", "loc", "local")
            )
        )
        val audioDir = appDir(OfflineDownloads.OFFLINE_AUDIO_DIR)
        val localDir = appDir(LibraryImport.LOCAL_AUDIO_DIR)
        val present = file(audioDir, "here.mp3", 64)
        val stub = file(audioDir, "stub.mp3", 0)
        val half = file(audioDir, "partial-0.mp3", 64)
        val unflagged = file(audioDir, "unflagged.mp3", 32)
        val privateCopy = file(localDir, "loc-1.mp3", 48)
        dao.insertTracks(
            listOf(
                SourceTrackEntity("t-here", "s-here", 0, present.absolutePath, localFilePath = present.absolutePath, isDownloaded = true),
                SourceTrackEntity("t-gone", "s-gone", 0, "/files/audiobooks/gone.mp3", localFilePath = "/files/audiobooks/gone.mp3", isDownloaded = true),
                SourceTrackEntity("t-stub", "s-stub", 0, stub.absolutePath, localFilePath = stub.absolutePath, isDownloaded = true),
                SourceTrackEntity("t-partial-0", "s-partial", 0, half.absolutePath, localFilePath = half.absolutePath, isDownloaded = true),
                SourceTrackEntity("t-partial-1", "s-partial", 1, "/files/audiobooks/partial-1.mp3", localFilePath = "/files/audiobooks/partial-1.mp3", isDownloaded = true),
                SourceTrackEntity("t-unflagged", "s-unflagged", 0, unflagged.absolutePath, localFilePath = unflagged.absolutePath, isDownloaded = false),
                SourceTrackEntity("t-loc", "s-loc", 0, privateCopy.absolutePath, localFilePath = privateCopy.absolutePath, isDownloaded = true)
            )
        )

        assertEquals(
            setOf("here", "unflagged", "loc"),
            downloadsWith(dao).bookIdsOnDevice()
        )
    }

    // #1163 — a book that has BOTH a source download and a merged local copy is
    // on the device through the local source even when the download part is
    // gone: the copy is readable, so the filter must not hide the book.
    @Test
    fun `bookIdsOnDevice includes a merged book whose local source is readable`() = runBlocking {
        val dao = FakeAudiobookDao(books = listOf(book("mixed", "https://fixtures.invalid/mixed", isDownloaded = true)))
        dao.insertSources(
            listOf(
                source("s-net", "mixed", "4read"),
                source("s-merged", "mixed", "local")
            )
        )
        val localDir = appDir(LibraryImport.LOCAL_AUDIO_DIR)
        val privateCopy = file(localDir, "merged-1.mp3", 48)
        dao.insertTracks(
            listOf(
                SourceTrackEntity("t-net", "s-net", 0, "/files/audiobooks/mixed-0.mp3", localFilePath = "/files/audiobooks/mixed-0.mp3", isDownloaded = false),
                SourceTrackEntity("t-merged", "s-merged", 0, privateCopy.absolutePath, localFilePath = privateCopy.absolutePath, isDownloaded = true)
            )
        )

        assertEquals(setOf("mixed"), downloadsWith(dao).bookIdsOnDevice())
    }
}
