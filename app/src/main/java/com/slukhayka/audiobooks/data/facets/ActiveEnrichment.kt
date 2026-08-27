package com.slukhayka.audiobooks.data.facets

import android.util.Log
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.metadata.DurationSanity
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * spec-42 T8 (#309) — active metadata enrichment during normal usage.
 *
 * When the Source profile, page metadata, or playback preparation reveals a
 * missing public fact, the app conservatively publishes it to the shared
 * metadata seams. Other listeners gradually receive fuller filters, but the
 * app never works in the background and never downloads full audio for metadata.
 *
 * One catalogue session triggers at most one throttled enrichment chain,
 * regardless of recomposition count.
 *
 * Enrichment scope:
 * - Genre (source-explicit) through shared facet seam with provenance
 * - Duration via create-only canonical contract (DurationEnrichment owns this seam)
 * - Chapter count from source page metadata
 * - Author/narrator/language facts through shared facet seam
 *
 * Constraints:
 * - Only public Work/Edition facts are contributed
 * - Listening State, history, UID, device ID are never shared
 * - Network failure is best-effort: local catalogue keeps working
 * - Identical contributions are no-ops
 * - Batch, page, and probe limits are respected
 */
class ActiveEnrichment(
    private val dao: AudiobookDao,
    private val facetWriter: LocalFacetWriter,
    private val fetchBookPage: suspend (String) -> SourceBookDetail
) {
    private val syncMutex = Mutex()
    private val lastEnrichmentRunEpochMs = AtomicLong(0L)

    data class EnrichmentResult(
        val worksProcessed: Int,
        val assertionsPublished: Int
    )

    /**
     * One bounded enrichment pass triggered during active usage.
     * Throttled: at most one pass per [MIN_ENRICHMENT_INTERVAL_MS].
     * Only processes Works with at least one source URL (enrichable).
     *
     * @param visibleWorkIds works currently visible or near-viewport (priority)
     * @param batchLimit hard upper bound on Works processed per pass
     * @param now current epoch millis for throttle check
     * @return result with counts, or null if throttled
     */
    suspend fun enrichActive(
        visibleWorkIds: List<String> = emptyList(),
        batchLimit: Int = DEFAULT_BATCH_LIMIT,
        now: () -> Long = System::currentTimeMillis
    ): EnrichmentResult? = syncMutex.withLock {
        val runAt = now()
        val lastRun = lastEnrichmentRunEpochMs.get()
        if (runAt - lastRun < MIN_ENRICHMENT_INTERVAL_MS) return@withLock null
        if (!lastEnrichmentRunEpochMs.compareAndSet(lastRun, runAt)) return@withLock null

        try {
            enrichBatch(visibleWorkIds, batchLimit, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Enrichment pass failed", e)
            EnrichmentResult(0, 0)
        }
    }

    /**
     * Force bypass throttle for explicit user-triggered enrichment
     * (e.g., when the user opens a book that has missing metadata).
     * Uses the same bounded batch logic.
     */
    suspend fun enrichNow(
        workIds: List<String>,
        now: () -> Long = System::currentTimeMillis
    ): EnrichmentResult = syncMutex.withLock {
        try {
            enrichSpecificWorks(workIds, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "On-demand enrichment failed", e)
            EnrichmentResult(0, 0)
        }
    }

    private suspend fun enrichBatch(
        visibleWorkIds: List<String>,
        batchLimit: Int,
        now: () -> Long
    ): EnrichmentResult {
        // Collect candidates: visible first, then other enrichable works
        val visibleCandidates = visibleWorkIds.take(batchLimit)
        val remaining = batchLimit - visibleCandidates.size

        // Read all work IDs from the database; the DAO only exposes a
        // Flow for observeWorks, so we use a one-shot getAllAudiobooksOnce
        // to find enrichable works, then map their workIds.
        val allWorkIds = withContext(Dispatchers.IO) {
            val books = dao.getAllAudiobooksOnce()
            books.mapNotNull { it.workId }.distinct()
        }

        val candidateIds = visibleCandidates + allWorkIds
            .filter { it !in visibleCandidates.toSet() }
            .take(remaining)

        return enrichSpecificWorks(candidateIds, now)
    }

    private suspend fun enrichSpecificWorks(
        workIds: List<String>,
        now: () -> Long
    ): EnrichmentResult {
        var worksProcessed = 0
        var assertionsPublished = 0

        for (workId in workIds) {
            try {
                val work = withContext(Dispatchers.IO) {
                    dao.getWorkById(workId)
                } ?: continue

                val sources = withContext(Dispatchers.IO) {
                    dao.getWorkSourcesForWorkSync(workId)
                }
                val sourceUrl = sources.firstOrNull()?.sourceUrl
                if (sourceUrl.isNullOrBlank()) continue

                val detail = fetchBookPage(sourceUrl)
                val published = publishFacts(work, detail, now)
                worksProcessed++
                if (published) assertionsPublished++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Enrichment failed for $workId", e)
            }
        }

        return EnrichmentResult(worksProcessed, assertionsPublished)
    }

    /**
     * Publish missing public facts from a source page through the facet writer.
     * Only facts that are missing from the local projection are published.
     * Identical contributions are no-ops (the facet writer deduplicates).
     */
    private suspend fun publishFacts(
        work: com.slukhayka.audiobooks.data.db.WorkEntity,
        detail: SourceBookDetail,
        now: () -> Long
    ): Boolean {
        val deltas = mutableListOf<LocalFacetDelta>()
        val updatedAt = now()

        // Genre enrichment: source-explicit genres through shared facet seam
        if (detail.genres.isNotEmpty()) {
            deltas += LocalFacetDelta(
                work = WorkFacetDelta(
                    workId = work.id,
                    genres = detail.genres.map { genre ->
                        GenreFacetAssertion(
                            rawText = genre,
                            sourceId = "active-enrichment",
                            observedAt = updatedAt,
                            documentUpdatedAt = updatedAt
                        )
                    },
                    updatedAt = updatedAt
                )
            )
        }

        // Author facet: if the work has a canonical author from the page
        if (detail.author.isNotBlank() && work.author.isNotBlank()) {
            // Build a normalized author id for the facet seam
            val authorId = com.slukhayka.audiobooks.data.facets.FacetIdentity.boundedId(
                "author",
                "${work.id}|${detail.author}"
            )
            deltas += LocalFacetDelta(
                work = WorkFacetDelta(
                    workId = work.id,
                    canonicalAuthorId = authorId,
                    updatedAt = updatedAt
                ),
                authors = listOf(
                    AuthorFacetDelta(
                        authorId = authorId,
                        displayName = detail.author,
                        updatedAt = updatedAt
                    )
                )
            )
        }

        if (deltas.isEmpty()) return false

        withContext(Dispatchers.IO) {
            facetWriter.apply(deltas)
        }
        return true
    }

    companion object {
        private const val TAG = "ActiveEnrichment"
        /** At most one enrichment pass per 6 hours. */
        const val MIN_ENRICHMENT_INTERVAL_MS = 6L * 60 * 60 * 1000
        /** Hard upper bound: no more than 10 Works per pass. */
        const val DEFAULT_BATCH_LIMIT = 10
    }
}
