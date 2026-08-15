package com.example.data.downloads

import android.content.Context
import android.util.Log
import com.example.data.catalog.SourceCatalog
import com.example.data.db.AudiobookDao
import com.example.data.source.headersFor
import com.example.data.source.sourceIdForUrl
import com.example.data.source.streamOnlyFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    private val sourceCatalog: SourceCatalog
) {

    /**
     * Outcome of an offline download attempt. `totalChapters == 0` means no
     * audio could be found at all (the caller shows a "no audio" message);
     * `downloadedChapters` counts how many chapters made it to disk.
     */
    data class OfflineDownloadResult(
        val downloadedChapters: Int,
        val totalChapters: Int
    )

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

        // Use the fallback-fetching catalog chapter fetch, NOT a raw Room
        // read: a catalogue book's chapters live on its source page and are
        // materialised on demand. Previously the raw read returned 0 chapters
        // for any book whose page had never been opened/played, and the method
        // silently returned — the Download button did nothing (observed
        // on-device: 183 of 214 books had no chapters in Room).
        val chapters = sourceCatalog.getChaptersList(bookId)
        val total = chapters.size
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
        var successCount = 0

        dao.updateDownloadState(bookId, isDownloaded = false, progress = 0.05f)

        coroutineScope {
            chapters.map { chapter ->
                async(Dispatchers.IO) {
                    val localFile = File(audioDir, "${chapter.id}.mp3")
                    var chapterOk = false

                    try {
                        if (!localFile.exists() || localFile.length() < 100) {
                            val streamUrl = chapter.streamUrl
                            if (streamUrl.startsWith("http")) {
                                val url = URL(streamUrl)
                                val connection = (url.openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 10000
                                    readTimeout = 20000
                                    requestMethod = "GET"
                                    setRequestProperty("User-Agent", OFFLINE_USER_AGENT)
                                    // Spec-10 T6 + spec-13 T2: the playerjs CDN
                                    // (redirectto.cc) 403s without the owning
                                    // source's Referer (audiobookmp3, sluhay,
                                    // sluhayknigi); other CDNs need none.
                                    headersFor(sourceId, streamUrl).forEach { (k, v) ->
                                        setRequestProperty(k, v)
                                    }
                                    instanceFollowRedirects = true
                                }

                                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                                    BufferedInputStream(connection.inputStream, 65536).use { input ->
                                        BufferedOutputStream(localFile.outputStream(), 65536).use { output ->
                                            val buffer = ByteArray(65536)
                                            var read: Int
                                            while (input.read(buffer).also { read = it } != -1) {
                                                output.write(buffer, 0, read)
                                            }
                                            output.flush()
                                        }
                                    }
                                    chapterOk = localFile.length() > 100
                                }
                                connection.disconnect()
                            }
                        } else if (localFile.length() > 100) {
                            // Already downloaded.
                            chapterOk = true
                        }
                    } catch (e: Exception) {
                        Log.w("OfflineDownloads", "Download failed for chapter ${chapter.id}: ${e.message}")
                        // Phase 2.5 hotfix (HI-001 / SF-001): the previous
                        // catch wrote a literal "OFFLINE_AUDIO_<id>" text
                        // marker into the .mp3 path and then set
                        // chapter.isDownloaded = true. The player tried to
                        // decode text and the user saw a "Downloaded" badge
                        // over unplayable content. Surface the failure
                        // instead.
                        if (localFile.exists()) localFile.delete()
                    }

                    val finished = completedCount.incrementAndGet()
                    val currentProgress = finished.toFloat() / total
                    dao.updateDownloadState(bookId, isDownloaded = false, progress = currentProgress)
                    dao.updateChapterDownloadState(
                        chapter.id,
                        isDownloaded = chapterOk,
                        filePath = if (chapterOk) localFile.absolutePath else null
                    )
                    if (chapterOk) successCount++
                }
            }.awaitAll()
        }

        val allOk = successCount == total
        dao.updateDownloadState(
            bookId,
            isDownloaded = allOk,
            progress = if (allOk) 1.0f else successCount.toFloat() / total
        )
        return OfflineDownloadResult(successCount, total)
    }

    suspend fun removeOfflineDownload(bookId: String) {
        val chapters = dao.getChaptersListForBook(bookId)
        chapters.forEach { ch ->
            ch.localFilePath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            dao.updateChapterDownloadState(ch.id, isDownloaded = false, filePath = null)
        }
        // The copies are gone; the hashes must not pretend they still exist,
        // otherwise a later re-import of the same files would be skipped as
        // "duplicate" and the book would stay unplayable (wayfinder #48+#50).
        dao.clearChapterContentHashes(bookId)
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
            dao.clearAllChaptersDownloadState()
        }
    }

    companion object {
        /** Single source of truth for the offline-audio directory name. */
        const val OFFLINE_AUDIO_DIR = "audiobooks"
        /** User-Agent used by the offline-download HttpURLConnection. */
        const val OFFLINE_USER_AGENT = "Mozilla/5.0 (Android; 4read-Audio-Engine/1.0)"
    }
}
