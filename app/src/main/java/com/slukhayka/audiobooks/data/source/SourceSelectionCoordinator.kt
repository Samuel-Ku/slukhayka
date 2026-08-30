package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.withTimeoutOrNull

/** The caller's intent; selection policy stays identical across entry points. */
enum class SourceOperation { PLAYBACK, OPEN_WORK, SEARCH, RECOMMENDATION, WORK_FEED, DOWNLOAD }

/** A candidate is inseparable from the Edition whose tracks it represents. */
data class SourceSelectionCandidate(
    val editionId: String,
    val source: SourceAccessCandidate
)

data class SourceSelectionRequest(
    val editionId: String,
    val operation: SourceOperation,
    val candidates: List<SourceSelectionCandidate>
)

/** The direct probe is intentionally transport-agnostic and easy to fake on JVM. */
fun interface SourceCandidateProber {
    suspend fun probe(candidate: SourceSelectionCandidate, budgetMillis: Long): Boolean
}

fun interface MonotonicClock {
    fun nowNanos(): Long
}

sealed interface SourceSelectionOutcome {
    data class Selected(val candidate: SourceSelectionCandidate) : SourceSelectionOutcome
    data class BrowserRequired(val candidate: SourceSelectionCandidate) : SourceSelectionOutcome
    data object Unavailable : SourceSelectionOutcome
}

/**
 * #429 — one runtime source-selection rule.
 *
 * It never treats a URL as permission to cross an Edition boundary: every
 * candidate is filtered by [SourceSelectionRequest.editionId] before ordering
 * or probing. Browser sources are a deliberate result, never an automatic UI
 * side effect. Direct and unknown sources share one monotonic ten-second
 * budget, so a long failure cannot grant the next probe a fresh timeout.
 */
class SourceSelectionCoordinator(
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() },
    private val budgetMillis: Long = DEFAULT_BUDGET_MILLIS
) {
    suspend fun select(
        request: SourceSelectionRequest,
        prober: SourceCandidateProber
    ): SourceSelectionOutcome {
        val ordered = SourceAccessPolicy.order(
            request.candidates
                .asSequence()
                .filter { it.editionId == request.editionId }
                .map { it.source }
                .toList()
        ).mapNotNull { source ->
            request.candidates.firstOrNull { it.editionId == request.editionId && it.source == source }
        }

        ordered.firstOrNull { it.source.localAvailable }?.let {
            return SourceSelectionOutcome.Selected(it)
        }

        val deadline = clock.nowNanos() + budgetMillis * NANOS_PER_MILLISECOND
        for (candidate in ordered) {
            if (candidate.source.accessMode == SourceAccessMode.BROWSER) continue
            val remainingMillis = ((deadline - clock.nowNanos()) / NANOS_PER_MILLISECOND)
            if (remainingMillis <= 0L) break
            if (withTimeoutOrNull(remainingMillis) { prober.probe(candidate, remainingMillis) } == true) {
                return SourceSelectionOutcome.Selected(candidate)
            }
        }

        return ordered
            .firstOrNull { it.source.accessMode == SourceAccessMode.BROWSER }
            ?.let(SourceSelectionOutcome::BrowserRequired)
            ?: SourceSelectionOutcome.Unavailable
    }

    private companion object {
        const val DEFAULT_BUDGET_MILLIS = 10_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
