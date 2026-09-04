package com.slukhayka.audiobooks.data.downloads

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.DownloadState
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.privacy.PacingParams
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #507 — cancelling a download never strands the Edition in DOWNLOADING.
 * Pure JVM (no Robolectric): FakeDao + temp dir + sized fake streams, the
 * pacing pause gated so the cancel lands mid-queue deterministically.
 */
class CancelDownloadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class CountingFetcher(
        streams: Map<String, Pair<ByteArray, Long?>>
    ) : FakeFetcher(emptyMap(), "", emptyMap(), streams) {
        override fun headContentLength(url: String, extraHeaders: Map<String, String>): Long? = 1024L
    }

    private suspend fun seedBook(dao: FakeAudiobookDao, bookId: String, chapterCount: Int, baseUrl: String) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = "Книга",
                    author = "Автор",
                    narrator = "Читець",
                    description = "",
                    coverDrawableRes = 0,
                    sourceUrl = baseUrl,
                    genre = ""
                )
            )
        )
        val editionId = "ed-$bookId"
        dao.insertEdition(
            EditionEntity(id = editionId, workId = bookId, narrator = "Читець", totalChapters = chapterCount)
        )
        dao.insertChapters(
            (0 until chapterCount).map { index ->
                ChapterEntity(
                    id = "$bookId-ch$index",
                    bookId = bookId,
                    chapterIndex = index,
                    title = "Розділ ${index + 1}",
                    durationSeconds = 60L,
                    editionId = editionId
                )
            }
        )
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = "sb-$bookId",
                    bookId = bookId,
                    editionId = editionId,
                    type = "soundbooks",
                    url = baseUrl
                )
            )
        )
        dao.insertTracks(
            (0 until chapterCount).map { index ->
                SourceTrackEntity(
                    id = "sb-$bookId-tr$index",
                    sourceId = "sb-$bookId",
                    trackIndex = index,
                    url = "$baseUrl/$index.mp3"
                )
            }
        )
    }

    private fun downloader(
        dao: FakeAudiobookDao,
        fetcher: CountingFetcher,
        pauseGate: CompletableDeferred<Unit>,
        pauseCalls: MutableList<Int>
    ): OfflineDownloads {
        val catalog = SourceCatalog(dao, emptyList(), LibraryImport(dao, null, emptyList()))
        return OfflineDownloads(
            dao = dao,
            context = null,
            sourceCatalog = catalog,
            fetcher = fetcher,
            pacing = PacingPolicy(PacingParams(0, 0, 1000, 60_000), kotlin.random.Random(0)),
            pauseFor = {
                pauseCalls += 1
                // Park every pacing pause after the first: workers run inline
                // on Unconfined, so chapter 0 always completes first while
                // chapters 1..N wait here — the cancel lands mid-queue with
                // exactly one chapter complete.
                if (pauseCalls.size >= 2) pauseGate.await()
            },
            downloadDispatcher = Dispatchers.Unconfined,
            filesDirOverride = tempFolder.root
        )
    }

    private fun audioDir() = java.io.File(tempFolder.root, OfflineDownloads.OFFLINE_AUDIO_DIR)

    @Test
    fun `cancel mid-queue keeps files, reports PAUSED, and resumes only missing`() = runTest {
        val dao = FakeAudiobookDao()
        val base = "https://sound-books.net/b1"
        seedBook(dao, "b1", 3, base)
        // Distinct bytes per chapter: identical content would legally
        // dedup-share one file (spec-37 T2) and break the file assertions.
        // NOTE: the ByteArray init lambda's `it` is the byte index, so the
        // chapter number must be mixed in explicitly.
        val bytes = (0..2).associate { chapter ->
            "$base/$chapter.mp3" to (
                ByteArray(2048) { i -> ((i + chapter * 77) % 251).toByte() } to 2048L
            )
        }
        val fetcher = CountingFetcher(bytes)
        val gate = CompletableDeferred<Unit>()
        val pauses = mutableListOf<Int>()
        val downloads = downloader(dao, fetcher, gate, pauses)

        val job = async { downloads.downloadAudiobookOffline("b1") }
        downloads.registerDownloadJob("b1", job)
        // Let chapter 0 finish and chapters 1..2 park in their pauses.
        var spins = 0
        while (pauses.size < 3 && spins++ < 200) {
            runCurrent()
            advanceUntilIdle()
        }
        assertEquals(3, pauses.size)
        assertTrue("queue must be mid-flight", downloads.hasActiveDownload())
        assertEquals(DownloadState.DOWNLOADING, dao.getAudiobookById("b1")?.downloadState)

        val cancelling = async { downloads.cancelDownload("b1") }
        advanceUntilIdle()
        cancelling.await()

        // The stuck-queue bug left DOWNLOADING here; #507 reports PAUSED.
        assertEquals(DownloadState.PAUSED, dao.getAudiobookById("b1")?.downloadState)
        assertFalse(downloads.hasActiveDownload())
        // Chapter 0 finished before the cancel — file and track stay.
        assertTrue(java.io.File(audioDir(), "b1-ch0.mp3").exists())
        assertEquals(1f / 3f, dao.getAudiobookById("b1")?.downloadProgress ?: -1f, 0.001f)
        // No half file is presented as ready.
        assertTrue(audioDir().listFiles()?.none { it.name.endsWith(".tmp") } ?: true)

        // Resume downloads only the missing chapters — chapter 0 is never refetched.
        gate.complete(Unit)
        val fetchedBefore = fetcher.sizedStreamRequests.toSet()
        assertEquals(setOf("$base/0.mp3"), fetchedBefore)
        val job2 = async { downloads.downloadAudiobookOffline("b1") }
        downloads.registerDownloadJob("b1", job2)
        advanceUntilIdle()
        val result = job2.await()
        downloads.unregisterDownloadJob("b1")

        assertEquals(3, result.totalChapters)
        assertEquals(setOf("$base/1.mp3", "$base/2.mp3"), fetcher.sizedStreamRequests.toSet() - fetchedBefore)
        println("DIAG dir=" + (audioDir().listFiles()?.map { it.name + ":" + it.length() } ?: "null"))
        println("DIAG tracks=" + dao.getTracksForBookSync("b1").map { it.trackIndex to (it.localFilePath ?: "null") })
        (0..2).forEach { assertTrue(java.io.File(audioDir(), "b1-ch$it.mp3").exists()) }
        assertEquals(DownloadState.IDLE, dao.getAudiobookById("b1")?.downloadState)
        assertTrue(dao.getAudiobookById("b1")?.isDownloaded == true)
        assertFalse(downloads.hasActiveDownload())
    }

    @Test
    fun `cancel is idempotent and cancel-without-progress stays IDLE`() = runTest {
        val dao = FakeAudiobookDao()
        seedBook(dao, "b2", 2, "https://sound-books.net/b2")
        val fetcher = CountingFetcher(emptyMap())
        val downloads = downloader(dao, fetcher, CompletableDeferred(), mutableListOf())

        downloads.cancelDownload("b2")
        assertEquals(DownloadState.IDLE, dao.getAudiobookById("b2")?.downloadState)
        downloads.cancelDownload("b2")
        assertEquals(DownloadState.IDLE, dao.getAudiobookById("b2")?.downloadState)
        assertFalse(downloads.hasActiveDownload())
    }
}
