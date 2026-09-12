package com.slukhayka.audiobooks.data.duration

import android.util.Log
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.metadata.DurationProvenance
import com.slukhayka.audiobooks.data.metadata.DurationSanity
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceRegistry
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * spec-18 T2 (#113) — the background duration enrichment pass, ported from
 * the old god repository onto the deep-module seams (ADR-0002): it reads
 * rows through [AudiobookDao] and fetches each candidate's page through that
 * book's OWN source adapter ([adapterFor]) — the same seam every other door
 * uses (no new parsing or transport code).
 *
 * Fills the duration column for books that lack one: bounded batch, one
 * pass per [MIN_ENRICHMENT_INTERVAL_MS], on the IO dispatcher. Each
 * candidate's page is fetched; only the real total duration is written to
 * the existing stats column (no schema change). A failing fetch or a page
 * without a duration leaves the row untouched and never aborts the batch.
 * Local imports (blank source URL) are skipped — there is no page to fetch
 * for them. #740: a browser-gated or scam source is skipped before any
 * request, and a source with no adapter on this device is an honest no-op —
 * discovery, cover back-fill and duration alike route by `sourceId`, never
 * by a fixed source.
 *
 * The throttle lives in memory only (the spec explicitly bans an enrichment
 * state table), so it is per app process — each launch gets its passes
 * again.
 */
class DurationEnrichment(
    private val dao: AudiobookDao,
    /**
     * #740 — the per-source adapter seam. The pass resolves each book's OWN
     * `sourceId` from its URL and fetches through that source's adapter —
     * never a fixed source. Null means this device has no adapter for the
     * source, so the book degrades honestly: no request, no crash.
     */
    private val adapterFor: (String) -> SourceAdapter?,
    // Spec-30 T4 (#219): the shared book-metadata store — a derived duration
    // is written back so the next listener reads it instead of re-fetching
    // the page. Null without Firebase keys: enrichment behaves exactly as
    // before. Best-effort by contract — a failing write never breaks a pass.
    private val sharedStore: SharedBookMetaStore? = null
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
            // #740: resolve the book's own source, then its adapter. A
            // browser-gated source needs a live listener session (ADR-0039)
            // and a scam source is never healed — both are skipped before any
            // request. A source without an adapter is an honest no-op.
            val sourceId = sourceIdForUrl(book.sourceUrl)
            if (isImplicitlyUnfetchable(sourceId)) continue
            val adapter = adapterFor(sourceId) ?: continue
            try {
                val detail = adapter.fetchBookPage(book.sourceUrl)
                val duration = detail.totalDurationSeconds
                // Any positive page-reported duration is real and written; the
                // honest-data row gate (DurationBuckets.hasKnownDuration)
                // filters later.
                if (duration != null && duration > 0L) {
                    dao.updateBookStats(book.id, book.totalChapters, duration)
                    // Spec-30 T4 (#219): a derived duration contributes to the
                    // shared base (sanity-gated), keyed by the same Edition id
                    // the read path uses — the next user never re-fetches.
                    if (DurationSanity.isPlausible(duration)) {
                        runCatching {
                            sharedStore?.putDuration(
                                editionId = EditionId.forBook(book.mergeKey ?: "", book.id, book.narrator),
                                durationSeconds = duration,
                                provenance = DurationProvenance(
                                    source = sourceId,
                                    derivedAt = now(),
                                    method = DurationProvenance.METHOD_SOURCE_METADATA
                                )
                            )
                        }
                    }
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

        /**
         * #740 — whether a background pass must NOT fetch this source even if
         * an adapter exists. The verdict is registry data (ADR-0038), never a
         * source-id literal: browser-gated sources require a live listener
         * session (ADR-0039), and scam sources are never healed (#741).
         * Unknown ids fall through to the adapter lookup, which decides.
         */
        fun isImplicitlyUnfetchable(sourceId: String): Boolean =
            SourceRegistry.isScam(sourceId) ||
                SourceAccessPolicy.modeFor(sourceId) == SourceAccessMode.BROWSER
    }
}
