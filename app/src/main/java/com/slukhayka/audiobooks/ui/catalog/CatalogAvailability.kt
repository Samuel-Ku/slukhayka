package com.slukhayka.audiobooks.ui.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Shared constants pinned by #455/#459 and the parity gate #461. */
object CatalogAvailabilityPolicy {
    const val POSITIVE_TTL_MS = 6L * 60 * 60 * 1_000
    const val NEGATIVE_TTL_MS = 15L * 60 * 1_000
    const val VERIFIED_PROFILE_TTL_MS = 24L * 60 * 60 * 1_000
    const val SOURCE_BUDGET_MS = 8_000L
    const val MAX_PARALLEL_SOURCES = 2

    fun isFresh(available: Boolean, observedAtMillis: Long, nowMillis: Long): Boolean =
        isWithin(observedAtMillis, nowMillis, if (available) POSITIVE_TTL_MS else NEGATIVE_TTL_MS)

    fun isVerifiedProfileFresh(observedAtMillis: Long, nowMillis: Long): Boolean =
        isWithin(observedAtMillis, nowMillis, VERIFIED_PROFILE_TTL_MS)

    private fun isWithin(observedAtMillis: Long, nowMillis: Long, ttlMillis: Long): Boolean =
        observedAtMillis >= 0L && nowMillis >= observedAtMillis && nowMillis - observedAtMillis < ttlMillis
}

data class EditionSourceCandidate(
    val sourceId: String,
    val editionId: String,
    val url: String = ""
)

enum class SourceAttemptVerdict {
    /** The audio element/player emitted its real playing event. */
    PLAYING,
    NO_NETWORK,
    TEMPORARY_FAILURE,
    AUDIO_MISSING,
    SESSION_REQUIRED,
    TIMEOUT
}

data class EditionPlaybackRaceResult(
    val source: EditionSourceCandidate?,
    val verdict: SourceAttemptVerdict
)

/**
 * Runs the at-most-two, 8-second Source race for one selected Edition.
 * Candidates from another Edition are rejected before a coroutine starts.
 * Input order is the already-frozen #429 capability order.
 */
class BoundedEditionPlaybackRace(
    private val scope: CoroutineScope,
    private val perSourceBudgetMs: Long = CatalogAvailabilityPolicy.SOURCE_BUDGET_MS
) {
    fun race(
        selectedEditionId: String,
        candidates: List<EditionSourceCandidate>,
        attempt: suspend (EditionSourceCandidate) -> SourceAttemptVerdict
    ): Deferred<EditionPlaybackRaceResult> = scope.async {
        coroutineScope {
            val eligible = candidates
                .filter { it.editionId == selectedEditionId }
                .take(CatalogAvailabilityPolicy.MAX_PARALLEL_SOURCES)
            if (eligible.isEmpty()) {
                return@coroutineScope EditionPlaybackRaceResult(null, SourceAttemptVerdict.AUDIO_MISSING)
            }

            val results = Channel<Pair<EditionSourceCandidate, SourceAttemptVerdict>>(Channel.UNLIMITED)
            val jobs = eligible.map { source ->
                launch {
                    try {
                        val verdict = withTimeoutOrNull(perSourceBudgetMs) { attempt(source) }
                            ?: SourceAttemptVerdict.TIMEOUT
                        results.send(source to verdict)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        results.send(source to SourceAttemptVerdict.TEMPORARY_FAILURE)
                    }
                }
            }

            val failures = mutableListOf<Pair<EditionSourceCandidate, SourceAttemptVerdict>>()
            repeat(eligible.size) {
                val result = results.receive()
                if (result.second == SourceAttemptVerdict.PLAYING) {
                    jobs.forEach { job -> if (job.isActive) job.cancel() }
                    return@coroutineScope EditionPlaybackRaceResult(result.first, result.second)
                }
                failures += result
            }
            val terminal = listOf(
                SourceAttemptVerdict.SESSION_REQUIRED,
                SourceAttemptVerdict.NO_NETWORK,
                SourceAttemptVerdict.AUDIO_MISSING,
                SourceAttemptVerdict.TEMPORARY_FAILURE,
                SourceAttemptVerdict.TIMEOUT
            ).first { wanted -> failures.any { it.second == wanted } }
            EditionPlaybackRaceResult(failures.firstOrNull { it.second == terminal }?.first, terminal)
        }
    }
}
