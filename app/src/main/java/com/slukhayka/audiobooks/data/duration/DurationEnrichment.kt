package com.slukhayka.audiobooks.data.duration

import android.util.Log
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * spec-18 T2 (#113) — the background duration enrichment pass, ported from
 * the old god repository onto the deep-module seams (ADR-0002): it reads
 * rows through [AudiobookDao] and fetches book pages through the injected
 * page fetch — in [com.slukhayka.audiobooks.App] that is the 4read source adapter, the
 * same seam every other door uses (no new parsing or transport code).
 *
 * Fills the duration column for books that lack one: bounded batch, one
 * pass per [MIN_ENRICHMENT_INTERVAL_MS], on the IO dispatcher. Each
 * candidate's page is fetched; only the real total duration is written to
 * the existing stats column (no schema change). A failing fetch or a page
 * without a duration leaves the row untouched and never aborts the batch.
 * Local imports (blank source URL) are skipped — there is no page to fetch
 * for them.
 *
 * The throttle lives in memory only (the spec explicitly bans an enrichment
 * state table), so it is per app process — each launch gets its passes
 * again.
 */
class DurationEnrichment(
    private val dao: AudiobookDao,
    private val fetchBookPage: suspend (String) -> SourceBookDetail
) {

    /** Timestamp of the last completed pass, as an atomic CAS gate. */
    private val lastEnrichmentRunEpochMs = AtomicLong(0L)

    /**
     * @return how many books received a duration this pass.
     */
    suspend fun enrichUnknownDurations(
        batchLimit: Int = DEFAULT_ENRICHMENT_BATCH,
        now: () -> Long = System::currentTimeMillis
    ): Int = withContext(Dispatchers.IO) {
        val runAt = now()
        val lastRun = lastEnrichmentRunEpochMs.get()
        if (runAt - lastRun < MIN_ENRICHMENT_INTERVAL_MS) return@withContext 0
        // Reserve the pass atomically: a concurrent trigger loses the CAS and
        // backs off, so overlapping passes can never fetch the same batch twice.
        if (!lastEnrichmentRunEpochMs.compareAndSet(lastRun, runAt)) return@withContext 0
        val candidates = dao.getAllAudiobooksOnce()
            .filter { book ->
                !DurationBuckets.hasKnownDuration(book.totalDurationSeconds) &&
                    book.sourceUrl.isNotBlank()
            }
            .take(batchLimit.coerceAtLeast(1))
        var enriched = 0
        for (book in candidates) {
            try {
                val detail = fetchBookPage(book.sourceUrl)
                val duration = detail.totalDurationSeconds
                // Any positive page-reported duration is real and written; the
                // honest-data row gate (DurationBuckets.hasKnownDuration)
                // filters later.
                if (duration != null && duration > 0L) {
                    dao.updateBookStats(book.id, book.totalChapters, duration)
                    enriched++
                }
            } catch (e: Exception) {
                Log.w("DurationEnrichment", "Duration enrichment failed for ${book.id}", e)
            }
        }
        enriched
    }

    companion object {
        const val MIN_ENRICHMENT_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val DEFAULT_ENRICHMENT_BATCH = 5
    }
}
