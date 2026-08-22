package com.slukhayka.audiobooks.data.downloads

import android.content.Context
import android.util.Log
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.OFFLINE_USER_AGENT
import com.slukhayka.audiobooks.data.source.headersFor
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.streamOnlyFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0002 — Offline Downloads: the deep module that owns downloading Works
 * for offline play, removing downloads, and clearing the audio cache.
 *
 * DAG edge (ticket #139): Offline Downloads → [SourceCatalog] — a
 * catalogue-only Work's chapters live on its source page and are materialised
 * on demand through [SourceCatalog.getChaptersList], never a raw DAO read (a
 * raw read historically returned zero chapters and the Download button
 * silently did nothing). Stream-only refusal ([streamOnlyFor]) and
 * chapter-hash clearing behave exactly as they did before the split.
 *
 * Constructing the module performs NO network I/O; the download paths only
 * touch the network when the caller invokes them.
 */
class OfflineDownloads(
    private val dao: AudiobookDao,
    private val context: Context? = null,
    private val sourceCatalog: SourceCatalog,
    // ADR-0006: the download path performs no HTTP of its own — it consumes
    // the shared fetcher's stream method, constructed with the offline user
    // agent from the download policy. Injectable so fixture tests serve
    // in-memory bytes with no network.
    private val fetcher: HttpFetcher = HttpFetcher(userAgent = OFFLINE_USER_AGENT)
) {

    /**
     * Outcome of an offline download attempt. `totalChapters == 0` means no
     * audio could be found at all (the caller shows a "no audio" message);
     * `downloadedChapters` counts how many chapters made it to disk.
     * `failedChapters` is derived for convenience (total - downloaded).
     */
    data class OfflineDownloadResult(
        val downloadedChapters: Int,
        val totalChapters: Int
    ) {
        val failedChapters: Int get() = totalChapters - downloadedChapters
    }

    suspend fun downloadAudiobookOffline(bookId: String): OfflineDownloadResult {
        // Spec-10 T6: a stream-only source must never download — refuse before
        // any state change or network I/O. The UI hides the action too; this
        // guard is defence in depth.
        val streamOnlyBook = dao.getAudiobookById(bookId)
        if (streamOnlyBook != null && streamOnlyFor(sourceIdForUrl(streamOnlyBook.sourceUrl))) {
            Log.w("OfflineDownloads", "downloadAudiobookOffline refused: book $bookId is stream-only")
            return OfflineDownloadResult(0, 0)
        }
        // Spec-13 T2: the track CDNs (shared `redirectto.cc`) 403 without the
        // owning source's Referer — derive it from the book, not the URL host.
        val sourceId = streamOnlyBook?.let { sourceIdForUrl(it.sourceUrl) } ?: "unknown"

        // Use the fallback-fetching catalog chapter fetch (chapters + their
        // tracks), NOT a raw Room read: a catalogue book's chapters live on
        // its source page and are materialised on demand. Previously the raw
        // read returned 0 chapters for any book whose page had never been
        // opened/played, and the method silently returned — the Download
        // button did nothing (observed on-device: 183 of 214 books had no
        // chapters in Room). ADR-0007: the download loop consumes the
        // chapter→track pairing and writes ONLY the track rows.
        val playable = sourceCatalog.getPlayableChapters(bookId)
        val total = playable.size
        if (total == 0) {
            Log.w("OfflineDownloads", "downloadAudiobookOffline: no chapters found for bookId=$bookId")
            return OfflineDownloadResult(0, 0)
        }

        // Phase 2.5 hotfix (SF-004 / SEC-008): the previous /sdcard fallback
        // was unreachable on Android 11+ scoped storage and would have failed
        // at runtime. The app always constructs this module with a real
        // Context, so fail loudly when it isn't there.
        val ctx = context ?: run {
            Log.e("OfflineDownloads", "downloadAudiobookOffline called without Context; aborting")
            dao.updateDownloadState(bookId, isDownloaded = false, progress = 0f)
            return OfflineDownloadResult(0, 0)
        }
        // Phase 2.5 hotfix (HI-002 / PERF-015): the cache size reader and
        // clearer look at filesDir/audio_downloads while this method wrote
        // to filesDir/audiobooks, so Clear Cache never cleared anything.
        // Align every component on the same constant directory name.
        val audioDir = File(ctx.filesDir, OFFLINE_AUDIO_DIR)
        if (!audioDir.exists()) audioDir.mkdirs()

        val completedCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val semaphore = Semaphore(3)

        dao.updateDownloadState(bookId, isDownloaded = false, progress = 0.05f)

        coroutineScope {
            playable.map { playableChapter ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val chapter = playableChapter.chapter
                        val track = playableChapter.track
                        val targetFile = File(audioDir, "${chapter.id}.mp3")
                        val tempFile = File(audioDir, "${chapter.id}.mp3.tmp")
                        var chapterOk = false

                        try {
                            // A chapter without a track (per-source topology
                            // mismatch) has nothing to download — stays failed.
                            if (track == null) {
                                // No playable stream for this chapter.
                            } else if (targetFile.exists() && targetFile.length() > 100) {
                                // Already downloaded and verified (minimal-size check).
                                // The temp file from a previous interrupted run, if any,
                                // is irrelevant — the target is already good.
                                chapterOk = true
                                // Clean any stale temp that might have been left
                                // by a previous crash before the rename.
                                if (tempFile.exists()) {
                                    try { tempFile.delete() } catch (_: Exception) {}
                                }
                            } else {
                                // Need to download — ensure stale files are gone.
                                if (tempFile.exists()) {
                                    try { tempFile.delete() } catch (_: Exception) {}
                                }
                                if (targetFile.exists() && targetFile.length() <= 100) {
                                    try { targetFile.delete() } catch (_: Exception) {}
                                }
                                val streamUrl = track.url
                                if (streamUrl.startsWith("http")) {
                                    // Spec-37 T1: use the sized transport so the
                                    // declared Content-Length can be verified.
                                    val sized = fetcher.getSizedStream(streamUrl, headersFor(sourceId, streamUrl))
                                    if (sized != null) {
                                        var streamClosed = false
                                        try {
                                            BufferedInputStream(sized.stream, 65536).use { input ->
                                                BufferedOutputStream(tempFile.outputStream(), 65536).use { output ->
                                                    val buffer = ByteArray(65536)
                                                    var read: Int
                                                    while (input.read(buffer).also { read = it } != -1) {
                                                        output.write(buffer, 0, read)
                                                    }
                                                    output.flush()
                                                }
                                            }
                                            streamClosed = true
                                            try { sized.stream.close() } catch (_: Exception) {}
                                            // Verification: when the server
                                            // declared a length, the body must
                                            // match it exactly. Shorter (or
                                            // longer) than declared → honestly
                                            // rejected, no target file, not
                                            // marked downloaded. When the
                                            // server omits the length, the
                                            // existing minimal-size threshold
                                            // applies (behaviour not worse).
                                            val expected = sized.contentLength
                                            val actual = tempFile.length()
                                            val valid = if (expected != null && expected >= 0) {
                                                actual == expected
                                            } else {
                                                actual > 100
                                            }
                                            if (valid) {
                                                if (targetFile.exists()) {
                                                    try { targetFile.delete() } catch (_: Exception) {}
                                                }
                                                val renamed = tempFile.renameTo(targetFile)
                                                if (!renamed) {
                                                    // Rename can fail across
                                                    // filesystems — fallback to copy.
                                                    try {
                                                        tempFile.copyTo(targetFile, overwrite = true)
                                                        tempFile.delete()
                                                    } catch (_: Exception) {
                                                        // Leave temp for next run to retry.
                                                    }
                                                }
                                                if (tempFile.exists()) {
                                                    try { tempFile.delete() } catch (_: Exception) {}
                                                }
                                                chapterOk = targetFile.exists() && targetFile.length() > 100
                                                if (chapterOk && expected != null && expected >= 0) {
                                                    if (targetFile.length() != expected) {
                                                        try { targetFile.delete() } catch (_: Exception) {}
                                                        chapterOk = false
                                                    }
                                                }
                                            } else {
                                                // Verification failed — short body.
                                                try { tempFile.delete() } catch (_: Exception) {}
                                                Log.w(
                                                    "OfflineDownloads",
                                                    "Verification failed for ${chapter.id}: expected=$expected actual=$actual"
                                                )
                                                chapterOk = false
                                            }
                                        } catch (e: Exception) {
                                            Log.w("OfflineDownloads", "Download failed for chapter ${chapter.id}: ${e.message}")
                                            try { tempFile.delete() } catch (_: Exception) {}
                                            if (!streamClosed) {
                                                try { sized.stream.close() } catch (_: Exception) {}
                                            }
                                            chapterOk = false
                                        }
                                    } else {
                                        // Fetcher returned null (network failure / non-200).
                                        if (tempFile.exists()) {
                                            try { tempFile.delete() } catch (_: Exception) {}
                                        }
                                        chapterOk = false
                                    }
                                } else {
                                    if (tempFile.exists()) {
                                        try { tempFile.delete() } catch (_: Exception) {}
                                    }
                                    chapterOk = false
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("OfflineDownloads", "Download failed for chapter ${chapter.id}: ${e.message}")
                            if (tempFile.exists()) {
                                try { tempFile.delete() } catch (_: Exception) {}
                            }
                            // Do not leave a partial target.
                            // The chapter is failed — honest state.
                        }

                        val finished = completedCount.incrementAndGet()
                        val currentProgress = finished.toFloat() / total
                        dao.updateDownloadState(bookId, isDownloaded = false, progress = currentProgress)
                        // ADR-0007: download state lives on the TRACK rows — the
                        // chapter rows never change on download.
                        track?.let {
                            dao.updateTrackDownloadState(
                                it.id,
                                isDownloaded = chapterOk,
                                filePath = if (chapterOk) targetFile.absolutePath else null
                            )
                        }
                        if (chapterOk) successCount.incrementAndGet()
                    }
                }
            }.awaitAll()
        }

        val success = successCount.get()
        val allOk = success == total
        dao.updateDownloadState(
            bookId,
            isDownloaded = allOk,
            progress = if (allOk) 1.0f else success.toFloat() / total
        )
        return OfflineDownloadResult(success, total)
    }

    suspend fun removeOfflineDownload(bookId: String) {
        // ADR-0007: the physical copies live on the TRACK rows.
        val tracks = dao.getTracksForBookSync(bookId)
        tracks.forEach { track ->
            track.localFilePath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            dao.updateTrackDownloadState(track.id, isDownloaded = false, filePath = null)
        }
        // The copies are gone; the hashes must not pretend they still exist,
        // otherwise a later re-import of the same files would be skipped as
        // "duplicate" and the book would stay unplayable (wayfinder #48+#50).
        dao.clearTrackContentHashesForBook(bookId)
        dao.updateDownloadState(bookId, isDownloaded = false, progress = 0f)
    }

    fun getAudioCacheSizeBytes(): Long {
        val ctx = context ?: return 0L
        var total = 0L
        // Phase 2.5 hotfix (HI-002 / PERF-015): previously read
        // filesDir/audio_downloads while downloadAudiobookOffline wrote
        // filesDir/audiobooks. Cache size was always 0 MB.
        val audioDir = File(ctx.filesDir, OFFLINE_AUDIO_DIR)
        if (audioDir.exists()) {
            audioDir.walkTopDown().forEach { file ->
                if (file.isFile) total += file.length()
            }
        }
        return total
    }

    suspend fun clearAllAudioCache() {
        val ctx = context
        withContext(Dispatchers.IO) {
            if (ctx != null) {
                // Phase 2.5 hotfix (HI-002 / PERF-015): same constant as
                // getAudioCacheSizeBytes and downloadAudiobookOffline.
                val audioDir = File(ctx.filesDir, OFFLINE_AUDIO_DIR)
                if (audioDir.exists()) {
                    audioDir.deleteRecursively()
                }
            }
            dao.markAllNotDownloaded()
            dao.clearAllTracksDownloadState()
        }
    }

    companion object {
        /** Single source of truth for the offline-audio directory name. */
        const val OFFLINE_AUDIO_DIR = "audiobooks"
    }
}
