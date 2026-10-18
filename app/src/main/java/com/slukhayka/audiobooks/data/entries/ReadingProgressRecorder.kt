package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.ReadthroughMapping
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ADR-0046 §§5–6 / spec-54 T16 (#876) / #1286 — the ONE reading-progress module.
 *
 * It owns both halves of the same concept:
 *  - the WRITE path — every decision belongs to [ReadthroughPolicy]; this class
 *    reads the pass, asks the policy, persists the answer and reports an honest
 *    outcome (`Recorded`, `Finished`, `Refused`, or `Failed` when storage
 *    refused the write). The journal is APPEND-ONLY: a record is never
 *    rewritten, and the pass keeps the unit it was observed in.
 *  - the READ path — [ReadingProgressPolicy] turns ONE snapshot of every pass
 *    into the active list and the yearly goal together ([State.Ready]). The
 *    screen reads [state] directly (ADR-0008) instead of mapping rows or
 *    refreshing two flows of its own.
 *
 * The [revision] guard makes the display ordering honest: a read that started
 * before a newer mutation may finish after it, but it can no longer publish its
 * older snapshot over the newer one. A read failure never becomes an empty
 * library or a missing year — the last good projection stays, marked
 * `refreshFailed`, and only a first-ever failure yields [State.Failed], which
 * the surface answers with an explicit retry. Cancellation is never an error.
 */
class ReadingProgressRecorder(
    private val dao: AudiobookDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val currentYear: () -> Int = { LocalDate.now().year }
) {

    sealed interface Result {
        data class Recorded(val readthrough: Readthrough) : Result
        data class Finished(val readthrough: Readthrough) : Result
        data class Refused(val reason: String) : Result

        /** The write did not land; the surface offers an explicit retry. */
        data class Failed(val reason: String) : Result
    }

    sealed interface State {
        /** No successful read yet. */
        data object Loading : State

        /** The last consistent projection; [refreshFailed] marks a later read. */
        data class Ready(
            val projection: ReadingProgressProjection,
            val refreshFailed: Boolean = false
        ) : State

        /** The FIRST read failed: nothing is known, so the surface retries. */
        data class Failed(val reason: String) : State
    }

    /**
     * Every read and every mutation bumps this; a read publishes only when the
     * revision it started from is still current, so a superseded refresh can
     * never put stale state back after a newer action.
     */
    private val revision = AtomicLong(0)

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Re-reads every pass and publishes both projections from that snapshot. */
    suspend fun refresh() = reload()

    suspend fun record(readthroughId: String, at: Long = now(), value: Int): Result =
        mutate(readthroughId) { pass -> ReadthroughPolicy.recordProgress(pass, at, value) }

    suspend fun finish(readthroughId: String, at: Long = now()): Result =
        mutate(readthroughId) { pass -> ReadthroughPolicy.finish(pass, at) }

    /**
     * A re-read: a NEW pass with a fresh journal, while the finished one stays
     * exactly as it was — history is never rewritten.
     */
    suspend fun restart(readthroughId: String, newId: String, startedAt: Long = now()): Result =
        mutate(readthroughId) { pass -> ReadthroughPolicy.restart(pass, newId, startedAt) }

    /** Every pass of one Work, as a caller that needs the history reads it. */
    suspend fun passesOfWork(workId: String): List<Readthrough> =
        dao.readthroughsForWork(workId).mapNotNull { with(ReadthroughMapping) { it.toModelOrNull() } }

    private suspend fun mutate(
        readthroughId: String,
        apply: (Readthrough) -> Readthrough?
    ): Result {
        val pass = try {
            load(readthroughId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (storage: Exception) {
            return Result.Failed(REASON_STORAGE)
        }
        if (pass == null) return Result.Refused(REASON_UNKNOWN_PASS)
        val next = apply(pass) ?: return Result.Refused(REASON_NOT_OPEN)
        try {
            persist(next)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (storage: Exception) {
            return Result.Failed(REASON_STORAGE)
        }
        // The save is DONE — only now is a newer snapshot allowed to win, and
        // only now do the projections follow it.
        revision.incrementAndGet()
        reload()
        return if (next.state == ReadingState.FINISHED) {
            Result.Finished(next)
        } else {
            Result.Recorded(next)
        }
    }

    private suspend fun reload() {
        val started = revision.get()
        val projection = try {
            readProjection()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (storage: Exception) {
            if (revision.get() == started) publishReadFailure()
            return
        }
        if (revision.get() == started) _state.value = State.Ready(projection)
    }

    private suspend fun readProjection(): ReadingProgressProjection {
        val passes = with(ReadthroughMapping) {
            dao.allReadthroughs().mapNotNull { it.toModelOrNull() }
        }
        return ReadingProgressPolicy.projection(passes, currentYear())
    }

    /**
     * A failed read keeps the known projection (marked stale for an explicit
     * retry) instead of pretending an empty library. Only when nothing was ever
     * read does it become [State.Failed].
     */
    private fun publishReadFailure() {
        val current = _state.value
        _state.value = if (current is State.Ready) {
            current.copy(refreshFailed = true)
        } else {
            State.Failed(REASON_STORAGE)
        }
    }

    private suspend fun load(readthroughId: String): Readthrough? =
        dao.readthroughById(readthroughId)?.let { with(ReadthroughMapping) { it.toModelOrNull() } }

    private suspend fun persist(pass: Readthrough) {
        with(ReadthroughMapping) { dao.upsertReadthrough(pass.toEntity()) }
    }

    companion object {
        const val REASON_UNKNOWN_PASS = "unknown-readthrough"
        const val REASON_NOT_OPEN = "readthrough-not-open"
        const val REASON_STORAGE = "storage-failed"
    }
}
