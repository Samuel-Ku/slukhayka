package com.slukhayka.audiobooks.data.downloads

import com.slukhayka.audiobooks.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #899 — the download manager's mapping is PURE: the listener-visible
 * statuses come from the existing persisted facts only (Library Entry state +
 * #397 track counts + the recovery flag), and the queue never invents an
 * item. The one-at-a-time contract is the only source of «У черзі».
 */
class DownloadQueueTest {

    private fun facts(
        bookId: String = "b1",
        title: String = "Книга",
        author: String = "Автор",
        isDownloaded: Boolean = false,
        downloadState: String = DownloadState.IDLE,
        downloadedChapters: Int = 0,
        totalChapters: Int = 10,
        progress: Float = 0f,
        requiresBrowserRefresh: Boolean = false,
        isLocal: Boolean = false
    ) = DownloadQueueFacts(
        bookId = bookId,
        title = title,
        author = author,
        isDownloaded = isDownloaded,
        downloadState = downloadState,
        downloadedChapters = downloadedChapters,
        totalChapters = totalChapters,
        progress = progress,
        requiresBrowserRefresh = requiresBrowserRefresh,
        isLocal = isLocal
    )

    // --- statusOf -----------------------------------------------------------

    @Test
    fun `idle book with nothing on disk is not a download at all`() {
        assertNull(DownloadQueue.statusOf(facts(), anotherDownloadActive = false))
        assertNull(DownloadQueue.statusOf(facts(), anotherDownloadActive = true))
    }

    @Test
    fun `the aggregate flag marks a finished download`() {
        assertEquals(
            DownloadQueueStatus.DONE,
            DownloadQueue.statusOf(facts(isDownloaded = true), anotherDownloadActive = false)
        )
    }

    @Test
    fun `all tracks on disk are done even when the aggregate flag lags`() {
        assertEquals(
            DownloadQueueStatus.DONE,
            DownloadQueue.statusOf(
                facts(downloadState = DownloadState.DOWNLOADING, downloadedChapters = 10),
                anotherDownloadActive = false
            )
        )
    }

    @Test
    fun `a complete copy wins over a stale recovery flag`() {
        assertEquals(
            DownloadQueueStatus.DONE,
            DownloadQueue.statusOf(
                facts(isDownloaded = true, requiresBrowserRefresh = true),
                anotherDownloadActive = false
            )
        )
    }

    @Test
    fun `a failed source queue is the honest error state`() {
        assertEquals(
            DownloadQueueStatus.ERROR,
            DownloadQueue.statusOf(
                facts(downloadState = DownloadState.PAUSED, requiresBrowserRefresh = true),
                anotherDownloadActive = false
            )
        )
    }

    @Test
    fun `a live loop is downloading`() {
        assertEquals(
            DownloadQueueStatus.DOWNLOADING,
            DownloadQueue.statusOf(
                facts(downloadState = DownloadState.DOWNLOADING, downloadedChapters = 3, progress = 0.3f),
                anotherDownloadActive = false
            )
        )
    }

    @Test
    fun `a paused download waits in the queue while another one runs`() {
        assertEquals(
            DownloadQueueStatus.QUEUED,
            DownloadQueue.statusOf(
                facts(downloadState = DownloadState.PAUSED, downloadedChapters = 2, progress = 0.2f),
                anotherDownloadActive = true
            )
        )
    }

    @Test
    fun `a paused download is paused when nothing else runs`() {
        assertEquals(
            DownloadQueueStatus.PAUSED,
            DownloadQueue.statusOf(
                facts(downloadState = DownloadState.PAUSED, downloadedChapters = 2, progress = 0.2f),
                anotherDownloadActive = false
            )
        )
    }

    // --- build --------------------------------------------------------------

    @Test
    fun `the queue excludes books with no download fact`() {
        val queue = DownloadQueue.build(
            facts = listOf(
                facts(bookId = "idle", downloadState = DownloadState.IDLE),
                facts(bookId = "live", downloadState = DownloadState.DOWNLOADING)
            ),
            activeBookId = "live"
        )
        assertEquals(listOf("live"), queue.map { it.bookId })
    }

    @Test
    fun `a locally imported copy is never a download queue item`() {
        // The copy is the ONLY copy: listing it as «Готово» would put a
        // delete affordance over the listener's own file.
        assertEquals(
            null,
            DownloadQueue.statusOf(
                facts(isDownloaded = true, isLocal = true),
                anotherDownloadActive = false
            )
        )
        assertTrue(
            DownloadQueue.build(
                facts = listOf(
                    facts(bookId = "local", isDownloaded = true, isLocal = true),
                    facts(bookId = "live", downloadState = DownloadState.DOWNLOADING)
                ),
                activeBookId = "live"
            ).map { it.bookId } == listOf("live")
        )
    }

    @Test
    fun `the queue orders active first and finished last`() {
        // While one download runs, every OTHER paused book is genuinely
        // waiting its turn (the app allows one download at a time), so both
        // paused books read «У черзі» here.
        val queue = DownloadQueue.build(
            facts = listOf(
                facts(bookId = "done", title = "Д", isDownloaded = true),
                facts(bookId = "paused", title = "П", downloadState = DownloadState.PAUSED, downloadedChapters = 1),
                facts(bookId = "error", title = "О", downloadState = DownloadState.PAUSED, requiresBrowserRefresh = true),
                facts(bookId = "queued", title = "Ч", downloadState = DownloadState.PAUSED, downloadedChapters = 1),
                facts(bookId = "live", title = "Ж", downloadState = DownloadState.DOWNLOADING)
            ),
            activeBookId = "live"
        )
        assertEquals("live", queue.first().bookId)
        assertEquals(
            listOf(
                DownloadQueueStatus.QUEUED,
                DownloadQueueStatus.QUEUED,
                DownloadQueueStatus.ERROR,
                DownloadQueueStatus.DONE
            ),
            queue.drop(1).map { it.status }
        )
    }

    @Test
    fun `with no active download a stopped queue reads as paused`() {
        val queue = DownloadQueue.build(
            facts = listOf(
                facts(bookId = "paused", downloadState = DownloadState.PAUSED, downloadedChapters = 1),
                facts(bookId = "error", downloadState = DownloadState.PAUSED, requiresBrowserRefresh = true),
                facts(bookId = "done", isDownloaded = true)
            ),
            activeBookId = null
        )
        assertEquals(
            listOf(DownloadQueueStatus.PAUSED, DownloadQueueStatus.ERROR, DownloadQueueStatus.DONE),
            queue.map { it.status }
        )
    }

    @Test
    fun `equal statuses order by title then book id`() {
        val queue = DownloadQueue.build(
            facts = listOf(
                facts(bookId = "b2", title = "Однакова", isDownloaded = true),
                facts(bookId = "b1", title = "Однакова", isDownloaded = true),
                facts(bookId = "b0", title = "Автор", isDownloaded = true)
            ),
            activeBookId = null
        )
        assertEquals(listOf("b0", "b1", "b2"), queue.map { it.bookId })
    }

    @Test
    fun `items carry the track counts, the size and an honest progress`() {
        val queue = DownloadQueue.build(
            facts = listOf(
                facts(
                    bookId = "paused",
                    downloadState = DownloadState.PAUSED,
                    downloadedChapters = 4,
                    totalChapters = 10,
                    progress = 0.4f
                ),
                facts(bookId = "done", isDownloaded = true, downloadedChapters = 10, progress = 0.2f)
            ),
            activeBookId = null,
            bytesOnDisk = mapOf("paused" to 4_000_000L, "done" to 10_000_000L)
        )
        val paused = queue.first { it.bookId == "paused" }
        assertEquals(4, paused.downloadedChapters)
        assertEquals(10, paused.totalChapters)
        assertEquals(4_000_000L, paused.bytesOnDisk)
        assertEquals(0.4f, paused.progress, 0.0001f)
        val done = queue.first { it.bookId == "done" }
        assertEquals(1f, done.progress, 0.0001f)
    }

    @Test
    fun `a missing size reads as zero and never negative`() {
        val queue = DownloadQueue.build(
            facts = listOf(facts(bookId = "live", downloadState = DownloadState.DOWNLOADING)),
            activeBookId = "live",
            bytesOnDisk = mapOf("live" to -5L)
        )
        assertEquals(0L, queue.single().bytesOnDisk)
    }

    // --- completedBookIds ---------------------------------------------------

    @Test
    fun `remove completed targets only finished downloads`() {
        val queue = DownloadQueue.build(
            facts = listOf(
                facts(bookId = "done", isDownloaded = true),
                facts(bookId = "error", downloadState = DownloadState.PAUSED, requiresBrowserRefresh = true),
                facts(bookId = "paused", downloadState = DownloadState.PAUSED, downloadedChapters = 1),
                facts(bookId = "live", downloadState = DownloadState.DOWNLOADING)
            ),
            activeBookId = "live"
        )
        assertEquals(listOf("done"), DownloadQueue.completedBookIds(queue))
        assertTrue(DownloadQueue.completedBookIds(emptyList()).isEmpty())
    }
}
