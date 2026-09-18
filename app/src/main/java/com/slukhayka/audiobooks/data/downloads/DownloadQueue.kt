package com.slukhayka.audiobooks.data.downloads

import com.slukhayka.audiobooks.data.db.DownloadState

/**
 * #899 — the download manager's queue as pure data: the states the listener
 * sees and the order they appear in. No Android, no Room, no new entity.
 *
 * Every fact comes from the EXISTING download state (ADR-0007 / #392 / #397):
 * the Library Entry carries `downloadState` + `downloadProgress`, the per-book
 * Source Track count carries the chapter totals, and the persisted 4read
 * browser-refresh queue ([OfflineDownloads.hasPendingBrowserRefresh]) is the
 * only failure fact the app writes down. The manager therefore invents no
 * state — it maps what is already persisted onto the listener-visible
 * statuses.
 */
enum class DownloadQueueStatus {
    /** Paused while ANOTHER download runs: the one-at-a-time queue holds it. */
    QUEUED,

    /** A live download loop is writing chapters right now. */
    DOWNLOADING,

    /** Stopped by the listener; the completed chapters stay for a resume. */
    PAUSED,

    /** Every Source Track of the book is on disk. */
    DONE,

    /** Stopped by a source refusal (403/404): a browser session must be renewed. */
    ERROR
}

/**
 * One book's download facts as the queue builder needs them — the Library
 * Entry's own state plus the per-book Source Track totals.
 */
data class DownloadQueueFacts(
    val bookId: String,
    val title: String,
    val author: String,
    val isDownloaded: Boolean,
    val downloadState: String,
    val downloadedChapters: Int,
    val totalChapters: Int,
    val progress: Float,
    val requiresBrowserRefresh: Boolean = false,
    /**
     * A locally imported book (blank source URL) is not a download: it was
     * never fetched, its copy is the ONLY copy, and «Прибрати» would destroy
     * it. It is excluded from the manager's queue (the book's own page owns
     * local-copy deletion).
     */
    val isLocal: Boolean = false
)

/** One listener-visible download: book, chapters, size, status. */
data class DownloadQueueItem(
    val bookId: String,
    val title: String,
    val author: String,
    val status: DownloadQueueStatus,
    val downloadedChapters: Int,
    val totalChapters: Int,
    val progress: Float,
    val bytesOnDisk: Long
)

object DownloadQueue {

    /** Active first, finished last — the order the manager lists them in. */
    private fun order(status: DownloadQueueStatus): Int = when (status) {
        DownloadQueueStatus.DOWNLOADING -> 0
        DownloadQueueStatus.QUEUED -> 1
        DownloadQueueStatus.PAUSED -> 2
        DownloadQueueStatus.ERROR -> 3
        DownloadQueueStatus.DONE -> 4
    }

    /**
     * The status of one book, or null when the book is not a download at all
     * (IDLE with nothing on disk) — the queue never invents an item.
     *
     * A complete copy wins over every other fact: a stale recovery flag on a
     * book whose chapters are all on disk must not read as an error, and a
     * book whose tracks are all ready is «Готово» even if the aggregate
     * `isDownloaded` flag lags behind (#397 reads the tracks).
     *
     * A [DownloadQueueFacts.isLocal] book is never a queue item.
     *
     * [anotherDownloadActive] is the app's one-download-at-a-time contract:
     * a paused book while another queue runs is genuinely waiting its turn.
     */
    fun statusOf(
        facts: DownloadQueueFacts,
        anotherDownloadActive: Boolean
    ): DownloadQueueStatus? {
        if (facts.isLocal) return null
        val complete = facts.isDownloaded ||
            (facts.totalChapters > 0 && facts.downloadedChapters >= facts.totalChapters)
        return when {
            complete -> DownloadQueueStatus.DONE
            facts.requiresBrowserRefresh -> DownloadQueueStatus.ERROR
            facts.downloadState == DownloadState.DOWNLOADING -> DownloadQueueStatus.DOWNLOADING
            facts.downloadState == DownloadState.PAUSED && anotherDownloadActive ->
                DownloadQueueStatus.QUEUED
            facts.downloadState == DownloadState.PAUSED -> DownloadQueueStatus.PAUSED
            else -> null
        }
    }

    /**
     * Builds the sorted manager queue from the raw facts. [activeBookId] is
     * the book whose download job is running (null when idle); [bytesOnDisk]
     * is the already-computed per-book size (the files are not in Room).
     */
    fun build(
        facts: List<DownloadQueueFacts>,
        activeBookId: String?,
        bytesOnDisk: Map<String, Long> = emptyMap()
    ): List<DownloadQueueItem> = facts
        .mapNotNull { fact ->
            val status = statusOf(
                facts = fact,
                anotherDownloadActive = activeBookId != null && activeBookId != fact.bookId
            ) ?: return@mapNotNull null
            DownloadQueueItem(
                bookId = fact.bookId,
                title = fact.title,
                author = fact.author,
                status = status,
                downloadedChapters = fact.downloadedChapters.coerceAtLeast(0),
                totalChapters = fact.totalChapters.coerceAtLeast(0),
                progress = if (status == DownloadQueueStatus.DONE) 1f
                else fact.progress.coerceIn(0f, 1f),
                bytesOnDisk = (bytesOnDisk[fact.bookId] ?: 0L).coerceAtLeast(0L)
            )
        }
        .sortedWith(
            compareBy(
                { order(it.status) },
                { it.title.lowercase() },
                { it.bookId }
            )
        )

    /**
     * «Прибрати завершені» — the ids the action removes. Only finished
     * downloads: a paused or failed queue keeps its partial files.
     */
    fun completedBookIds(items: List<DownloadQueueItem>): List<String> =
        items.filter { it.status == DownloadQueueStatus.DONE }.map { it.bookId }
}
