package com.example.data.universe

import com.example.data.db.AudiobookDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Spec-26 T7 (#181) — the background refresh pass: enumerates the cached
 * series memberships whose TIER has expired and re-resolves them by
 * priority, so the universe view tracks Wikidata changes even for books no
 * one opens. Priority = most overdue first (the largest overshoot of the
 * membership's (now - resolvedAt) over its tier).
 *
 * Each re-resolution rides [SeriesUniverses.resolveForWork] — the exact same
 * path as a book open — so it re-persists the Room cache AND writes the
 * fresh result back to the shared base (spec-26 T6 provenance write),
 * spreading the update to all clients (AC5).
 *
 * Throttled: [paceMillis] between re-resolutions (kept below Wikidata's 429
 * window — the provider's own retry-with-backoff catches any that slip
 * through), and one pass is capped at [maxPerRun] memberships so a single
 * run is bounded; the caller re-invokes it (the periodic startup loop) to
 * drain the rest.
 */
class UniverseRefreshPass(
    private val dao: AudiobookDao,
    private val resolver: SeriesUniverses,
    private val tierTtlMillis: (Boolean, Int?, Int) -> Long = UniverseRefreshTier::tierTtlMillis,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxPerRun: Int = 50,
    private val paceMillis: Long = 2_000L
) {

    /** Re-resolves the most-overdue expired memberships; returns how many. */
    suspend fun runOnce(): Int = withContext(Dispatchers.IO) {
        val currentNow = now()
        val year = UniverseRefreshTier.epochYear(currentNow)
        val allSeries = dao.getAllSeries()
        val byId = allSeries.associateBy { it.id }
        val universeSizes = allSeries
            .filter { it.universeId != null }
            .groupBy { it.universeId!! }
            .mapValues { it.value.size }

        val overdue = dao.getAllSeriesMembers()
            .mapNotNull { membership ->
                val series = byId[membership.seriesId]
                val isTail = series?.universeId?.let { universeId ->
                    series.positionInUniverse != null &&
                        series.positionInUniverse == universeSizes[universeId]
                } ?: false
                val tier = tierTtlMillis(isTail, series?.publicationYear, year)
                val overshoot = currentNow - (membership.resolvedAt ?: 0L) - tier
                if (overshoot >= 0) membership to overshoot else null
            }
            .sortedByDescending { it.second }
            .take(maxPerRun)

        var resolved = 0
        for ((membership, _) in overdue) {
            resolver.resolveForWork(membership.workId)
            resolved++
            if (paceMillis > 0) delay(paceMillis)
        }
        resolved
    }
}
