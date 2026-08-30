package com.slukhayka.audiobooks.data.downloads

import android.content.Context
import android.util.Log
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.contentHashOf
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.YouTubeTracks
import com.slukhayka.audiobooks.data.source.headersFor
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.streamOnlyFor
import com.slukhayka.audiobooks.data.db.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    // the shared fetcher's stream method. Spec-38 (#252): the fetcher now
    // rides the browser identity (system WebView UA) and the privacy route,
    // so downloads look like ordinary browsing too — the old dedicated
    // «4read-Audio-Engine» agent was a fingerprint. Injectable so fixture
    // tests serve in-memory bytes with no network.
    private val fetcher: HttpFetcher = HttpFetcher(),
    // Spec-38 T5 (#257): the human rhythm lives in the privacy door's policy;
    // this loop only consumes it (a random pause, then a burst slot) and owns
    // no pacing thresholds of its own. Injectable so tests pin exact timing
    // with a seeded generator and a virtual clock.
    private val pacing: PacingPolicy = PacingPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    // The one suspension the rhythm costs. Injectable because the chapter
    // workers run on Dispatchers.IO — real time, invisible to a test
    // scheduler — so tests substitute a virtual pause (and, for cancellation,
    // an endless one) instead of sleeping.
    private val pauseFor: suspend (Long) -> Unit = { pauseMillis -> delay(pauseMillis) },
    // Spec 2026-08-26: a persisted YouTube watch URL resolves per-use to a
    // concrete audio stream URL before the fetch — the signed URL expires
    // (~6h) and is never stored. Identity for plain URLs; null (extraction
    // failed) fails the chapter honestly. Injectable so tests pin the seam.
    // LAST: keeps every pre-existing positional call site valid.
    private val streamUrlResolver: suspend (String) -> String? = { url -> url },
    /** Local WebView cookies, scoped by [headersFor] to 4read audio hosts. */
    private val cookieProvider: () -> String = { "" }
) {

    private val recoveryPrefs by lazy {
        context?.getSharedPreferences("offline_download_recovery", Context.MODE_PRIVATE)
    }

    /** A 4read queue survives Activity/process recreation without a schema change. */
    fun hasPendingBrowserRefresh(bookId: String): Boolean =
        recoveryPrefs?.getBoolean("4read_$bookId", false) == true

    fun clearPendingBrowserRefresh(bookId: String) {
        recoveryPrefs?.edit()?.remove("4read_$bookId")?.apply()
    }

    private fun markPendingBrowserRefresh(bookId: String) {
        recoveryPrefs?.edit()?.putBoolean("4read_$bookId", true)?.apply()
    }

    /**
     * Outcome of an offline download attempt. `totalChapters == 0` means no
     * audio could be found at all (the caller shows a "no audio" message);
     * `downloadedChapters` counts how many chapters were freshly fetched from
     * the network, `reusedChapters` counts URL-deduped (no network), and
     * `sharedChapters` counts hash-deduped (shared file) chapters. `failedChapters`
     * is derived.
     */
    data class OfflineDownloadResult(
        val downloadedChapters: Int,
        val totalChapters: Int,
        val sharedChapters: Int = 0,
        val reusedChapters: Int = 0,
        /** 4read returned no usable stream; the user must refresh in WebView. */
        val requiresBrowserRefresh: Boolean = false
    ) {
        val failedChapters: Int get() = totalChapters - downloadedChapters - sharedChapters - reusedChapters
    }

    /** #392 — estimated total size for UI before download. */
    data class EstimatedSize(
        val totalBytes: Long?,
        val isApproximate: Boolean,
        val knownCount: Int,
        val totalCount: Int
    )

    /** #392 — live bytes progress during download (chapters + MB). */
    data class DownloadBytesProgress(
        val completedChapters: Int,
        val totalChapters: Int,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val isApproximate: Boolean
    )

    private val _downloadBytesProgress = MutableStateFlow<Map<String, DownloadBytesProgress>>(emptyMap())
    val downloadBytesProgress: StateFlow<Map<String, DownloadBytesProgress>> = _downloadBytesProgress

    // #394 — active download jobs per bookId, for pause / cancel.
    private val _activeDownloadJobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    /**
     * #394 — Register an externally-launched download job. The ViewModel calls
     * this after `viewModelScope.launch { downloadAudiobookOffline(...) }` so
     * that [pauseDownload] and [cancelDownload] can cancel it.
     */
    fun registerDownloadJob(bookId: String, job: Job) {
        _activeDownloadJobs.value = _activeDownloadJobs.value + (bookId to job)
    }

    /** #394 — Unregister a download job (called in the finally block). */
    fun unregisterDownloadJob(bookId: String) {
        _activeDownloadJobs.value = _activeDownloadJobs.value - bookId
    }

    private suspend fun computeHash(file: File): String? = try {
        FileInputStream(file).use { contentHashOf(it) }
    } catch (e: Exception) {
        Log.w("OfflineDownloads", "hash failed for ${file.name}: ${e.message}")
        null
    }

    /** The burst budget is per domain (spec-38 T5); a URL that refuses to
     *  parse counts as its own domain rather than bypassing the gate. */
    private fun domainOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (_: Exception) {
        url
    }

    /** #392 — estimate total bytes via HEAD before download (for UI). */
    suspend fun estimateOfflineSize(bookId: String): EstimatedSize {
        val book = dao.getAudiobookById(bookId)
        val sourceId = book?.let { sourceIdForUrl(it.sourceUrl) } ?: "unknown"
        val playable = try { sourceCatalog.getPlayableChapters(bookId) } catch (_: Exception) { emptyList() }
        if (playable.isEmpty()) return EstimatedSize(null, isApproximate = false, knownCount = 0, totalCount = 0)
        var total: Long = 0
        var known = 0
        for (pc in playable) {
            val url = pc.track?.url ?: continue
            if (!url.startsWith("http")) continue
            val len = try { fetcher.headContentLength(url, headersFor(sourceId, url)) } catch (_: Exception) { null }
            if (len != null && len >= 0) {
                total += len
                known++
            }
        }
        return if (known == 0) EstimatedSize(null, isApproximate = false, knownCount = 0, totalCount = playable.size)
        else EstimatedSize(total, isApproximate = known < playable.size, knownCount = known, totalCount = playable.size)
    }

    suspend fun downloadAudiobookOffline(bookId: String): OfflineDownloadResult {
        val streamOnlyBook = dao.getAudiobookById(bookId)

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
        // The selected PlayableChapter carries the physical Source identity,
        // so a direct secondary Source cannot accidentally inherit the
        // primary book row's Referer or stream-only policy.
        val sourceId = playable.firstOrNull()?.sourceId
            ?: streamOnlyBook?.let { sourceIdForUrl(it.sourceUrl) }
            ?: "unknown"
        if (streamOnlyFor(sourceId)) {
            Log.w("OfflineDownloads", "downloadAudiobookOffline refused: book $bookId is stream-only")
            return OfflineDownloadResult(0, 0)
        }

        // Phase 2.5 hotfix (SF-004 / SEC-008): the previous /sdcard fallback
        // was unreachable on Android 11+ scoped storage and would have failed
        // at runtime. The app always constructs this module with a real
        // Context, so fail loudly when it isn't there.
        val ctx = context ?: run {
            Log.e("OfflineDownloads", "downloadAudiobookOffline called without Context; aborting")
            dao.updateDownloadStateWithState(bookId, isDownloaded = false, progress = 0f, state = DownloadState.IDLE)
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
        val sharedCount = AtomicInteger(0)
        val reusedCount = AtomicInteger(0)
        val browserRefreshRequired = AtomicInteger(0)
        // A browser-backed 4read refresh is a user-visible recovery step. Run
        // its queue serially and stop scheduling new chapters after the first
        // failed request, leaving the completed tracks intact for a later
        // retry. Direct sources keep the existing bounded parallelism.
        val semaphore = Semaphore(if (sourceId == "4read") 1 else 3)

        // #392 — estimate total bytes via HEAD for MB display (uses same
        // HttpFetcher + headers + privacy route as the download loop)
        val estimated = try { estimateOfflineSize(bookId) } catch (_: Exception) { EstimatedSize(null, false, 0, total) }
        val estimatedTotalBytes = estimated.totalBytes
        val isApproximate = estimated.isApproximate
        val downloadedBytes = AtomicLong(0)
        _downloadBytesProgress.value = _downloadBytesProgress.value + (bookId to DownloadBytesProgress(0, total, 0, estimatedTotalBytes, isApproximate))

        dao.updateDownloadStateWithState(bookId, isDownloaded = false, progress = 0.05f, state = DownloadState.DOWNLOADING)

        coroutineScope {
            playable.map { playableChapter ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val chapter = playableChapter.chapter
                        val track = playableChapter.track
                        val targetFile = File(audioDir, "${chapter.id}.mp3")
                        val tempFile = File(audioDir, "${chapter.id}.mp3.tmp")
                        var chapterOk = false
                        var isReused = false
                        var isShared = false
                        var computedHash: String? = null

                        // Once 4read tells us that the browser session is
                        // stale, pause the remaining queue. Already completed
                        // tracks remain valid and the next explicit attempt
                        // resumes from the first failed chapter.
                        if (sourceId == "4read" && browserRefreshRequired.get() == 1) {
                            val finished = completedCount.incrementAndGet()
                            dao.updateDownloadState(
                                bookId,
                                isDownloaded = false,
                                progress = finished.toFloat() / total
                            )
                            return@withPermit
                        }

                        try {
                            // A chapter without a track (per-source topology
                            // mismatch) has nothing to download — stays failed.
                            if (track == null) {
                                // A topology failure is not proof that a browser
                                // session is stale, so it stays an honest failure.
                            } else if (targetFile.exists() && targetFile.length() > 100) {
                                // Already downloaded and verified (minimal-size check).
                                chapterOk = true
                                if (tempFile.exists()) {
                                    try { tempFile.delete() } catch (_: Exception) {}
                                }
                                // Ensure hash is stored for future dedup (spec-37 T2).
                                if (track.contentHash == null) {
                                    computedHash = computeHash(targetFile)
                                    if (computedHash != null) {
                                        dao.updateTrackContentHash(track.id, computedHash)
                                    }
                                }
                            } else {
                                // Check URL-based reuse before network (spec-37 T2).
                                var urlReused = false
                                val existingByUrl = dao.getDownloadedTrackByUrl(track.url)
                                if (existingByUrl != null && existingByUrl.id != track.id && existingByUrl.localFilePath != null) {
                                    val existingFile = File(existingByUrl.localFilePath)
                                    if (existingFile.exists() && existingFile.length() > 100) {
                                        val existingHash = existingByUrl.contentHash ?: computeHash(existingFile)?.also {
                                            dao.updateTrackContentHash(existingByUrl.id, it)
                                        }
                                        // Share the file without network request.
                                        dao.updateTrackDownloadState(track.id, true, existingFile.absolutePath)
                                        if (existingHash != null) {
                                            dao.updateTrackContentHash(track.id, existingHash)
                                        }
                                        chapterOk = true
                                        isReused = true
                                        urlReused = true
                                        // Clean any stale temp.
                                        if (tempFile.exists()) {
                                            try { tempFile.delete() } catch (_: Exception) {}
                                        }
                                    }
                                }
                                if (!urlReused) {
                                    // Need to download — ensure stale files are gone.
                                    if (tempFile.exists()) {
                                        try { tempFile.delete() } catch (_: Exception) {}
                                    }
                                    if (targetFile.exists() && targetFile.length() <= 100) {
                                        try { targetFile.delete() } catch (_: Exception) {}
                                    }
                                    // Spec 2026-08-26: YouTube watch URLs resolve
                                    // right before the fetch; null = honest
                                    // failed chapter (never a fabricated file).
                                    val streamUrl = runCatching { streamUrlResolver(track.url) }
                                        .onFailure { Log.w("OfflineDownloads", "resolve failed for ${track.url}: ${it.message}") }
                                        .getOrNull()
                                        ?: track.url.takeIf { !YouTubeTracks.isYouTubeWatchUrl(it) }
                                    if (streamUrl != null && streamUrl.startsWith("http")) {
                                        // Spec-38 T5 (#257): the human rhythm —
                                        // every fresh fetch first waits a random
                                        // pause from the policy range (never
                                        // zero), then asks the policy for a
                                        // burst slot on the track's domain. A
                                        // refusal costs another policy pause;
                                        // this loop owns no thresholds. The
                                        // pause is a suspension point, so a
                                        // cancel during it aborts promptly.
                                        pauseFor(pacing.nextPauseMillis())
                                        while (!pacing.allowsRequest(domainOf(streamUrl), nowMillis())) {
                                            pauseFor(pacing.nextPauseMillis())
                                        }
                                        // Spec-37 T1: use the sized transport so the
                                        // declared Content-Length can be verified.
                                        val response = fetcher.getSizedStreamResult(
                                            streamUrl,
                                            headersFor(sourceId, streamUrl, cookieProvider())
                                        )
                                        val sized = response.sizedStream
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
                                                        try {
                                                            tempFile.copyTo(targetFile, overwrite = true)
                                                            tempFile.delete()
                                                        } catch (_: Exception) {
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
                                                    if (chapterOk) {
                                                        // Spec-37 T2: compute and store hash, then check for hash-based dedup.
                                                        computedHash = computeHash(targetFile)
                                                        if (computedHash != null) {
                                                            dao.updateTrackContentHash(track.id, computedHash)
                                                            // Check if another track already has same hash (different content address, same bytes).
                                                            val duplicate = dao.getTrackByContentHash(computedHash)
                                                            if (duplicate != null && duplicate.id != track.id && duplicate.localFilePath != null) {
                                                                val dupFile = File(duplicate.localFilePath)
                                                                if (dupFile.exists() && dupFile.absolutePath != targetFile.absolutePath) {
                                                                    // Share the existing file, delete the duplicate we just created.
                                                                    try { targetFile.delete() } catch (_: Exception) {}
                                                                    dao.updateTrackDownloadState(track.id, true, dupFile.absolutePath)
                                                                    // Keep hash already stored
                                                                    isShared = true
                                                                    // chapterOk stays true, but file is now shared
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else {
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
                                            if (tempFile.exists()) {
                                                try { tempFile.delete() } catch (_: Exception) {}
                                            }
                                            chapterOk = false
                                            if (sourceId == "4read" && response.status in setOf(403, 404)) {
                                                browserRefreshRequired.set(1)
                                            }
                                        }
                                    } else {
                                        if (tempFile.exists()) {
                                            try { tempFile.delete() } catch (_: Exception) {}
                                        }
                                        chapterOk = false
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            // Spec-38 T5 (#257): a cancel during a pacing pause
                            // (or mid-stream) must stop the loop honestly, not
                            // be miscounted as a failed chapter.
                            throw e
                        } catch (e: Exception) {
                            Log.w("OfflineDownloads", "Download failed for chapter ${chapter.id}: ${e.message}")
                            if (tempFile.exists()) {
                                try { tempFile.delete() } catch (_: Exception) {}
                            }
                        }

                        val finished = completedCount.incrementAndGet()
                        val currentProgress = finished.toFloat() / total
                        // #392 — MB progress: sum actual file lengths for completed chapters
                        if (chapterOk) {
                            val bytesForChapter = try {
                                when {
                                    targetFile.exists() && targetFile.length() > 0 -> targetFile.length()
                                    isReused || isShared -> {
                                        val path = track?.let {
                                            dao.getTracksForBookSync(bookId).firstOrNull { it.id == track.id }?.localFilePath
                                        } ?: targetFile.absolutePath
                                        try { File(path).length().takeIf { it > 0 } ?: 0L } catch (_: Exception) { 0L }
                                    }
                                    else -> 0L
                                }
                            } catch (_: Exception) { 0L }
                            if (bytesForChapter > 0) downloadedBytes.addAndGet(bytesForChapter)
                        }
                        _downloadBytesProgress.value = _downloadBytesProgress.value + (
                            bookId to DownloadBytesProgress(
                                completedChapters = finished,
                                totalChapters = total,
                                downloadedBytes = downloadedBytes.get(),
                                totalBytes = estimatedTotalBytes,
                                isApproximate = isApproximate
                            )
                            )
                        dao.updateDownloadStateWithState(
                            bookId,
                            isDownloaded = false,
                            progress = currentProgress,
                            state = DownloadState.DOWNLOADING
                        )
                        // ADR-0007: download state lives on the TRACK rows — the
                        // chapter rows never change on download.
                        // For URL-reused chapters we already updated the track; for
                        // hash-shared we also already updated; for fresh downloads
                        // we need to set the path (which may now be a shared path).
                        if (!isReused) {
                            // For non-reused, we set the path to targetFile if ok,
                            // but if it was hash-shared, the path is already the dup's path.
                            // To avoid overwriting the shared path, only update if not already shared.
                            if (!isShared) {
                                track?.let {
                                    dao.updateTrackDownloadState(
                                        it.id,
                                        isDownloaded = chapterOk,
                                        filePath = if (chapterOk) targetFile.absolutePath else null
                                    )
                                }
                            }
                            // For hash-shared, the track already points to dup file; nothing more.
                            // But ensure hash is stored (already done above).
                            if (isShared) {
                                // Already updated to dup path; ensure we don't overwrite
                            }
                        } else {
                            // Reused via URL: track already updated to existing file path above.
                            // No further update needed, but ensure progress already handled.
                        }
                        // For already-existed case, we already ensured hash and chapterOk, but track still needs to be marked as downloaded if not already?
                        // That case had targetFile exists, so track should already be marked downloaded from previous run. However, if this is a fresh book whose target was already present from a previous download of same book (re-download), we marked chapterOk true but didn't update track. The track may already be marked downloaded, but we should ensure.
                        if (chapterOk && targetFile.exists() && track != null) {
                            // If track was already downloaded but hash missing, we already stored hash.
                            // Ensure track points to file if it was previously null (e.g., after clear but file remains? Not possible).
                            val currentTrack = dao.getTracksForBookSync(bookId).firstOrNull { it.id == track.id }
                            if (currentTrack?.localFilePath == null) {
                                dao.updateTrackDownloadState(track.id, true, targetFile.absolutePath)
                            }
                        }
                        when {
                            isShared -> sharedCount.incrementAndGet()
                            isReused -> reusedCount.incrementAndGet()
                            chapterOk -> successCount.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        // Post-book hash dedup: after all chapters, ensure any remaining
        // hash matches across books are shared (covers the case where two
        // cards of same rendition have same content but different URLs and were
        // not caught per-chapter due to race). Re-check each track of this book.
        // This also handles the case where the book's chapters were already
        // downloaded but hashes now match a newer book's files.
        try {
            val myTracks = dao.getTracksForBookSync(bookId).filter { it.isDownloaded && it.contentHash != null }
            for (myTrack in myTracks) {
                val hash = myTrack.contentHash ?: continue
                val duplicate = dao.getTrackByContentHash(hash) ?: continue
                if (duplicate.id == myTrack.id) continue
                if (duplicate.localFilePath == null || duplicate.localFilePath == myTrack.localFilePath) continue
                val dupFile = File(duplicate.localFilePath)
                val myFile = myTrack.localFilePath?.let { File(it) }
                if (dupFile.exists() && myFile != null && myFile.exists() && myFile.absolutePath != dupFile.absolutePath) {
                    // Prefer the older file (duplicate) as canonical.
                    try { myFile.delete() } catch (_: Exception) {}
                    dao.updateTrackDownloadState(myTrack.id, true, dupFile.absolutePath)
                    // Count as shared if not already counted? This post-pass sharing
                    // should be reflected in the result if it wasn't already.
                    // To avoid double-counting, only increment if myTrack was
                    // previously counted as downloadedNew.
                    // For simplicity, we don't adjust counters here; the per-chapter
                    // hash sharing already counted. This post-pass is for consistency
                    // of files, not for result reporting.
                }
            }
        } catch (e: Exception) {
            Log.w("OfflineDownloads", "post-book hash dedup failed: ${e.message}")
        }

        val success = successCount.get()
        val shared = sharedCount.get()
        val reused = reusedCount.get()
        val totalSuccess = success + shared + reused
        // If some chapters were already downloaded before this run (target exists),
        // they were counted as success via the early `targetFile.exists()` path.
        // Those are part of `success` as well. For honest reporting, we consider
        // the book fully downloaded if totalSuccess == total.
        val allOk = totalSuccess == total
        val needsBrowserRefresh = browserRefreshRequired.get() == 1
        if (needsBrowserRefresh) {
            markPendingBrowserRefresh(bookId)
        } else if (allOk) {
            clearPendingBrowserRefresh(bookId)
        }
        // But also need to consider already-existing files that were not counted
        // as success in this run? Actually they were counted as success (chapterOk true and successCount incremented).
        // So totalSuccess includes them.
        dao.updateDownloadStateWithState(
            bookId,
            isDownloaded = allOk,
            progress = if (allOk) 1.0f else totalSuccess.toFloat() / total,
            state = if (needsBrowserRefresh) DownloadState.PAUSED else DownloadState.IDLE
        )
        // #392 — clear bytes progress for this book after finish
        _downloadBytesProgress.value = _downloadBytesProgress.value - bookId
        return OfflineDownloadResult(
            downloadedChapters = success,
            totalChapters = total,
            sharedChapters = shared,
            reusedChapters = reused,
            requiresBrowserRefresh = needsBrowserRefresh
        )
    }

    // =====================================================================
    // #394 — Pause / Continue / Cancel download controls
    // =====================================================================

    /**
     * #394 — Pause an active download. Cancels the coroutine, keeps completed
     * tracks' localFilePath + contentHash, deletes only `*.tmp` files of
     * in-progress chapters, and sets downloadState = PAUSED.
     *
     * The PAUSED state is persisted in Room (`library_entries.downloadState`)
     * so it survives process death and app restart.
     */
    suspend fun pauseDownload(bookId: String) {
        val job = _activeDownloadJobs.value[bookId]
        if (job != null && job.isActive) {
            // Cancel the active download coroutine.
            job.cancel()
            // Wait briefly for the coroutine to reach its cancellation point.
            try { job.join() } catch (_: Exception) {}
            _activeDownloadJobs.value = _activeDownloadJobs.value - bookId
        }

        // Clean up temp files of in-progress chapters (always, even without
        // an active job — a previous crash may have left tmp files behind).
        val ctx = context
        if (ctx != null) {
            val audioDir = File(ctx.filesDir, OFFLINE_AUDIO_DIR)
            if (audioDir.exists()) {
                audioDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { tmp ->
                    try { tmp.delete() } catch (_: Exception) {}
                }
            }
        }

        // Set PAUSED state — completed tracks keep their localFilePath + contentHash.
        val current = dao.getAudiobookById(bookId)
        val progress = current?.downloadProgress ?: 0f
        if (progress > 0f || current?.downloadState == DownloadState.DOWNLOADING) {
            dao.updateDownloadStateWithState(bookId, false, progress, DownloadState.PAUSED)
        }
        _downloadBytesProgress.value = _downloadBytesProgress.value - bookId
    }

    /**
     * #394 — Continue a paused download. Resumes only chapters that are not
     * yet downloaded (localFilePath is null or isDownloaded is false), preserving
     * URL/hash dedup for already-completed chapters.
     *
     * No-op if the download is not in PAUSED state.
     */
    suspend fun continueDownload(bookId: String): OfflineDownloadResult {
        val entry = dao.getAudiobookById(bookId) ?: return OfflineDownloadResult(0, 0)
        if (entry.downloadState != DownloadState.PAUSED) return OfflineDownloadResult(0, 0)
        // Resume the download — downloadAudiobookOffline already skips chapters
        // whose targetFile exists and is valid (>100 bytes), so calling it again
        // effectively downloads only the missing chapters.
        return downloadAudiobookOffline(bookId)
    }

    /**
     * #394 — Cancel a download and remove all downloaded files for the edition.
     * Clears localFilePath on all tracks, resets downloadProgress to 0, removes
     * the notification, and sets downloadState = IDLE.
     *
     * Also cancels any active download coroutine.
     */
    suspend fun cancelDownload(bookId: String) {
        // Cancel active download if any.
        val job = _activeDownloadJobs.value[bookId]
        if (job != null && job.isActive) {
            job.cancel()
            try { job.join() } catch (_: Exception) {}
        }
        _activeDownloadJobs.value = _activeDownloadJobs.value - bookId

        // Delete all downloaded files for this book's tracks.
        removeOfflineDownload(bookId)
        // Ensure downloadState is reset to IDLE (removeOfflineDownload only
        // resets isDownloaded and downloadProgress, not the state enum).
        dao.updateDownloadStateWithState(bookId, isDownloaded = false, progress = 0f, state = DownloadState.IDLE)
    }

    /**
     * Whether a download is currently active (not paused, not idle) for the
     * given bookId. The notification service uses this to decide which
     * buttons to show.
     */
    fun isDownloading(bookId: String): Boolean {
        val job = _activeDownloadJobs.value[bookId]
        return job != null && job.isActive
    }

    /** One download at a time is the UI contract; notification actions honour it too. */
    fun hasActiveDownload(): Boolean = _activeDownloadJobs.value.values.any { it.isActive }

    /**
     * Whether a download is paused for the given bookId.
     */
    suspend fun isPaused(bookId: String): Boolean {
        val entry = dao.getAudiobookById(bookId)
        return entry?.downloadState == DownloadState.PAUSED
    }

    suspend fun removeOfflineDownload(bookId: String) {
        // ADR-0007: the physical copies live on the TRACK rows.
        val tracks = dao.getTracksForBookSync(bookId)
        tracks.forEach { track ->
            val path = track.localFilePath
            if (path != null) {
                // Spec-37 T2: delete file only when no other downloaded track still references it.
                val referencing = try { dao.getTracksByFilePath(path) } catch (e: Exception) { emptyList() }
                // referencing includes this track; if only this track references it, it's last reference.
                val otherRefs = referencing.filter { it.id != track.id }
                if (otherRefs.isEmpty()) {
                    val file = File(path)
                    if (file.exists()) {
                        try { file.delete() } catch (_: Exception) {}
                    }
                }
            }
            dao.updateTrackDownloadState(track.id, isDownloaded = false, filePath = null)
        }
        // The copies are gone; the hashes must not pretend they still exist,
        // otherwise a later re-import of the same files would be skipped as
        // "duplicate" and the book would stay unplayable (wayfinder #48+#50).
        dao.clearTrackContentHashesForBook(bookId)
        // #394: use updateDownloadStateWithState to also reset downloadState
        // to IDLE. Without this, a PAUSED download that gets its files
        // removed would stay stuck in PAUSED state with no files on disk.
        dao.updateDownloadStateWithState(bookId, isDownloaded = false, progress = 0f, state = DownloadState.IDLE)
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
