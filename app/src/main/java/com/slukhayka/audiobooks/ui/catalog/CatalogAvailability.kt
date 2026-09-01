package com.slukhayka.audiobooks.ui.catalog

import com.slukhayka.audiobooks.data.catalog.CatalogAvailabilityPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class EditionSourceCandidate(
    val sourceId: String,
    val editionId: String,
    val url: String = ""
)

enum class SourceAttemptVerdict {
    /** Source import and media preflight succeeded; it may enter the one Player. */
    READY,
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
    private val perSourceBudgetMs: Long = CatalogAvailabilityPolicy.SOURCE_BUDGET_MS
) {
    /**
     * Prepares at most two Sources concurrently, then gives the singleton
     * Player to ready candidates one at a time. A failed Player start cannot
     * starve the already-prepared sibling.
     */
    suspend fun racePrepared(
        selectedEditionId: String,
        candidates: List<EditionSourceCandidate>,
        prepare: suspend (EditionSourceCandidate) -> SourceAttemptVerdict,
        play: suspend (EditionSourceCandidate) -> SourceAttemptVerdict
    ): EditionPlaybackRaceResult = coroutineScope {
        val eligible = candidates
            .filter { it.editionId == selectedEditionId }
            .take(CatalogAvailabilityPolicy.MAX_PARALLEL_SOURCES)
        if (eligible.isEmpty()) {
            return@coroutineScope EditionPlaybackRaceResult(null, SourceAttemptVerdict.AUDIO_MISSING)
        }

        val prepared = Channel<Pair<EditionSourceCandidate, SourceAttemptVerdict>>(Channel.UNLIMITED)
        val preparationJobs = eligible.map { source ->
            launch {
                val verdict = try {
                    withTimeoutOrNull(perSourceBudgetMs) { prepare(source) }
                        ?: SourceAttemptVerdict.TIMEOUT
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SourceAttemptVerdict.TEMPORARY_FAILURE
                }
                prepared.send(source to verdict)
            }
        }

        val failures = mutableListOf<Pair<EditionSourceCandidate, SourceAttemptVerdict>>()
        repeat(eligible.size) {
            val result = prepared.receive()
            if (result.second != SourceAttemptVerdict.READY) {
                failures += result
                return@repeat
            }
            val playVerdict = try {
                withTimeoutOrNull(perSourceBudgetMs) { play(result.first) }
                    ?: SourceAttemptVerdict.TIMEOUT
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SourceAttemptVerdict.TEMPORARY_FAILURE
            }
            if (playVerdict == SourceAttemptVerdict.PLAYING) {
                preparationJobs.forEach { job -> if (job.isActive) job.cancel() }
                return@coroutineScope EditionPlaybackRaceResult(result.first, playVerdict)
            }
            failures += result.first to playVerdict
        }

        val terminalOrder = listOf(
            SourceAttemptVerdict.SESSION_REQUIRED,
            SourceAttemptVerdict.NO_NETWORK,
            SourceAttemptVerdict.AUDIO_MISSING,
            SourceAttemptVerdict.TEMPORARY_FAILURE,
            SourceAttemptVerdict.TIMEOUT
        )
        val terminal = terminalOrder.first { wanted -> failures.any { it.second == wanted } }
        EditionPlaybackRaceResult(failures.firstOrNull { it.second == terminal }?.first, terminal)
    }

    suspend fun race(
        selectedEditionId: String,
        candidates: List<EditionSourceCandidate>,
        attempt: suspend (EditionSourceCandidate) -> SourceAttemptVerdict
    ): EditionPlaybackRaceResult = coroutineScope {
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
