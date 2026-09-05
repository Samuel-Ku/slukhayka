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

    @Test fun `cancel removes orphan temps from a previous process regardless of generation`() = runTest {
        val dao = FakeAudiobookDao()
        seedBook(dao, "b1", 1, "https://sound-books.net/b1")
        audioDir().mkdirs()
        val orphans = listOf("b1-ch0.mp3.100.tmp", "b1-ch0.mp3.previous-session.500.tmp")
            .map { File(audioDir(), it).also { file -> file.writeBytes(ByteArray(200)) } }
        val otherBook = File(audioDir(), "other-ch0.mp3.previous-session.500.tmp").also { it.writeBytes(ByteArray(200)) }
        val downloads = downloader(dao, CountingFetcher(emptyMap()), CompletableDeferred(Unit), mutableListOf())
        downloads.cancelDownload("b1")
        orphans.forEach { assertFalse(it.exists()) }
        assertTrue(otherBook.exists())
    }

    @Test(timeout = 45_000)
    fun `late blocking stream cannot overwrite resumed files or unregister replacement`() : Unit = kotlinx.coroutines.runBlocking {
        val dao = FakeAudiobookDao()
        val base = "https://sound-books.net/overlap"
        seedBook(dao, "b1", 2, base)
        audioDir().mkdirs()
        File(audioDir(), "b1-ch0.mp3").writeBytes(ByteArray(1024) { 7 })
        dao.updateTrackDownloadState("sb-b1-tr0", true, File(audioDir(), "b1-ch0.mp3").absolutePath)
        val entered = Array(2) { java.util.concurrent.CountDownLatch(1) }
        val release = Array(2) { java.util.concurrent.CountDownLatch(1) }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val fetcher = object : FakeFetcher() {
            override fun headContentLength(url: String, extraHeaders: Map<String, String>): Long = 1024L
            override fun getSizedStreamResult(url: String, extraHeaders: Map<String, String>): com.slukhayka.audiobooks.data.source.HttpFetcher.SizedStreamResult {
                val attempt = calls.getAndIncrement()
                val bytes = ByteArray(1024) { (attempt + 20).toByte() }
                val delegate = bytes.inputStream()
                val input = object : java.io.InputStream() {
                    override fun read(): Int = error("Buffered read expected")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        entered[attempt].countDown()
                        check(release[attempt].await(30, java.util.concurrent.TimeUnit.SECONDS))
                        return delegate.read(buffer, offset, length)
                    }
                }
                return com.slukhayka.audiobooks.data.source.HttpFetcher.SizedStreamResult(
                    200, com.slukhayka.audiobooks.data.source.HttpFetcher.SizedStream(input, 1024L)
                )
            }
        }
        val downloads = OfflineDownloads(
            dao, sourceCatalog = SourceCatalog(dao, emptyList(), LibraryImport(dao, null, emptyList())),
            fetcher = fetcher,
            pacing = PacingPolicy(PacingParams(0, 0, 1000, 60_000), kotlin.random.Random(0)),
            pauseFor = {}, downloadDispatcher = Dispatchers.IO, filesDirOverride = tempFolder.root
        )
        fun start() = async(Dispatchers.IO) {
            val owner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
            downloads.registerDownloadJob("b1", owner)
            try { downloads.downloadAudiobookOffline("b1") }
            finally { downloads.unregisterDownloadJob("b1", owner) }
        }
        val old = start()
        try {
            assertTrue(entered[0].await(10, java.util.concurrent.TimeUnit.SECONDS))
            downloads.cancelDownload("b1") // Returns after the bounded join, while read remains blocked.
            assertFalse(old.isCompleted)
            val replacement = start()
            try {
                assertTrue(entered[1].await(10, java.util.concurrent.TimeUnit.SECONDS))
                val replacementTemp = audioDir().listFiles()!!.single { it.name.endsWith(".tmp") }
                release[0].countDown()
                old.join()
                assertTrue("Old finally must not unregister replacement", downloads.isDownloading("b1"))
                assertTrue("Old cleanup must not delete replacement temp", replacementTemp.exists())
                assertEquals(DownloadState.DOWNLOADING, dao.getAudiobookById("b1")?.downloadState)
                release[1].countDown()
                replacement.await()
                assertTrue(File(audioDir(), "b1-ch1.mp3").readBytes().contentEquals(ByteArray(1024) { 21 }))
                assertTrue(File(audioDir(), "b1-ch0.mp3").readBytes().contentEquals(ByteArray(1024) { 7 }))
            } finally { release[1].countDown(); replacement.cancel(); replacement.join() }
        } finally { release[0].countDown(); old.cancel(); old.join() }
    }

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
        downloads.unregisterDownloadJob("b1", job2)

        assertEquals(3, result.totalChapters)
        assertEquals(setOf("$base/1.mp3", "$base/2.mp3"), fetcher.sizedStreamRequests.toSet() - fetchedBefore)
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

    @Test
    fun `cancel derives resumable progress from files when aggregate and track state are stale`() = runTest {
        val dao = FakeAudiobookDao()
        seedBook(dao, "b3", 2, "https://sound-books.net/b3")
        val downloads = downloader(
            dao,
            CountingFetcher(emptyMap()),
            CompletableDeferred(),
            mutableListOf()
        )
        // Existing installs can carry the older stable bookId_ch_N filename
        // even when the current logical Chapter ID has since been rehydrated.
        val readyFile = File(audioDir(), "b3_ch_1.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2048) { 7 })
        }
        // Reproduces the device state: the physical chapter is ready while
        // both its track row and the aggregate Library Entry are still stale.
        dao.updateDownloadStateWithState("b3", false, 0f, DownloadState.IDLE)

        downloads.cancelDownload("b3")

        assertEquals(DownloadState.PAUSED, dao.getAudiobookById("b3")?.downloadState)
        assertEquals(0.5f, dao.getAudiobookById("b3")?.downloadProgress ?: -1f, 0.001f)
        assertTrue(readyFile.exists())
    }

    @Test
    fun `cancel trusts a valid track file even when its downloaded flag is stale`() = runTest {
        val dao = FakeAudiobookDao()
        seedBook(dao, "b6", 2, "https://sound-books.net/b6")
        val downloads = downloader(
            dao,
            CountingFetcher(emptyMap()),
            CompletableDeferred(),
            mutableListOf()
        )
        val readyFile = File(audioDir(), "migrated-name.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2048) { 6 })
        }
        // Cancellation can land after the durable path write but before the
        // boolean/aggregate state catches up. The playable file is the truth.
        dao.updateTrackDownloadState("sb-b6-tr0", false, readyFile.absolutePath)
        dao.updateDownloadStateWithState("b6", false, 0f, DownloadState.IDLE)

        downloads.cancelDownload("b6")

        assertEquals(DownloadState.PAUSED, dao.getAudiobookById("b6")?.downloadState)
        assertEquals(0.5f, dao.getAudiobookById("b6")?.downloadProgress ?: -1f, 0.001f)
        assertTrue(readyFile.exists())
    }

    @Test
    fun `cancel deletes only the target books temporary files`() = runTest {
        val dao = FakeAudiobookDao()
        seedBook(dao, "b4", 2, "https://sound-books.net/b4")
        seedBook(dao, "b5", 2, "https://sound-books.net/b5")
        val downloads = downloader(
            dao,
            CountingFetcher(emptyMap()),
            CompletableDeferred(),
            mutableListOf()
        )
        val targetTemp = File(audioDir(), "b4-ch0.mp3.tmp").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(128) { 4 })
        }
        val otherBookTemp = File(audioDir(), "b5-ch0.mp3.tmp").apply {
            writeBytes(ByteArray(128) { 5 })
        }

        downloads.cancelDownload("b4")

        assertFalse(targetTemp.exists())
        assertTrue(otherBookTemp.exists())
    }
}
