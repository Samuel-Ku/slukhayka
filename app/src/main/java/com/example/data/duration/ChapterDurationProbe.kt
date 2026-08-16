package com.example.data.duration

import android.util.Log
import com.example.data.catalog.SourceCatalog
import com.example.data.db.AudiobookDao
import com.example.data.db.BookRow
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * spec-24 T8 (#169) — the background chapter-duration probing pass, sibling
 * of [DurationEnrichment] (same policy shape: atomic single-flight CAS
 * throttle, bounded batch, per-row failure isolation, honest writes).
 *
 * Chapter durations stop being playback-only knowledge: for every library
 * book with at least one unknown-duration chapter and a stream URL (local
 * imports have none — nothing to probe), the pass probes each unknown track
 * through [StreamProber]: HEAD → Content-Length, ranged GET of the stream
 * head → CBR MPEG frame header (bitrate + sample rate, two-frame gate —
 * VBR/Xing is never guessed), then writes duration = size × 8 / bitrate
 * through the existing per-chapter seam ([AudiobookDao.updateChapterDuration])
 * — the chapter lists (book page, «Розділи» sheet) pick it up from the Room
 * flow automatically, no UI change.
 *
 * When every chapter of a book is now known and the book's total duration
 * was unknown (0), the book total becomes the sum of the chapters — a
 * site-provided total is never overwritten.
 *
 * The pass is bounded (batch ≥ 10 books, aggressive by decision) and
 * throttled to one pass per hour in memory only (same doctrine as the
 * enrichment pass); a failing probe leaves the row untouched and never
 * aborts the batch. The chapter→track pairing rides the catalog seam
 * ([SourceCatalog.getPlayableChapters]) — the same 1:1-by-index pairing the
 * player uses, never a duplicated copy.
 */
class ChapterDurationProbe(
    private val dao: AudiobookDao,
    private val playableChapters: suspend (String) -> List<SourceCatalog.PlayableChapter>,
    private val prober: StreamProber
) {

    /** Timestamp of the last completed pass, as an atomic CAS gate. */
    private val lastProbeRunEpochMs = AtomicLong(0L)

    /**
     * @return how many chapters received a duration this pass.
     */
    suspend fun probeUnknownChapters(
        batchLimit: Int = DEFAULT_PROBE_BATCH,
        now: () -> Long = System::currentTimeMillis
    ): Int = withContext(Dispatchers.IO) {
        val runAt = now()
        val lastRun = lastProbeRunEpochMs.get()
        if (runAt - lastRun < MIN_PROBE_INTERVAL_MS) return@withContext 0
        // Reserve the pass atomically: a concurrent trigger loses the CAS and
        // backs off, so overlapping passes can never probe the same book twice.
        if (!lastProbeRunEpochMs.compareAndSet(lastRun, runAt)) return@withContext 0

        // Candidates: books with at least one unknown-duration chapter (0 is
        // the unknown placeholder) and a stream URL — local imports (blank
        // sourceUrl, nothing to probe) never reach the transport.
        val unknownBookIds = dao.getBookIdsWithUnknownChapterDurations().toSet()
        val candidates = dao.getAllAudiobooksOnce()
            .filter { it.id in unknownBookIds && it.sourceUrl.isNotBlank() }
            .take(batchLimit.coerceAtLeast(1))

        var probed = 0
        for (book in candidates) {
            try {
                probed += probeBook(book)
            } catch (e: Exception) {
                Log.w("ChapterDurationProbe", "Chapter duration probe failed for ${book.id}", e)
            }
        }
        probed
    }

    /** @return how many chapters of one book received a duration. */
    private suspend fun probeBook(book: BookRow): Int {
        var probed = 0
        for (pair in playableChapters(book.id)) {
            val chapter = pair.chapter
            if (chapter.durationSeconds > 0L) continue // already known
            val track = pair.track ?: continue // no stream for this chapter
            if (track.url.isBlank()) continue // local copy / no probe target
            // Best-effort: a failing probe leaves the row untouched.
            val result = prober.probe(track.url) ?: continue
            val durationSeconds = result.contentLength * 8L / (result.frame.bitrateKbps * 1000L)
            if (durationSeconds <= 0L) continue
            dao.updateChapterDuration(chapter.id, durationSeconds)
            probed++
        }
        // Book total: only when it was unknown (0) and EVERY chapter is now
        // known — a site-provided total is never overwritten.
        if (book.totalDurationSeconds == 0L) {
            val chapters = dao.getChaptersListForBook(book.id)
            if (chapters.isNotEmpty() && chapters.all { it.durationSeconds > 0L }) {
                dao.updateBookStats(book.id, chapters.size, chapters.sumOf { it.durationSeconds })
            }
        }
        return probed
    }

    companion object {
        /** One pass per hour — the probe is cheap (a HEAD + a head window per track). */
        const val MIN_PROBE_INTERVAL_MS = 1L * 60 * 60 * 1000
        /** Aggressive by decision: ≥ 10 books per pass. */
        const val DEFAULT_PROBE_BATCH = 10
    }
}
