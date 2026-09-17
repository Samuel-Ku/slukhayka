package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.ReadthroughMapping

/**
 * ADR-0046 §§5–6 / spec-54 T16 (#876) — the WRITE path of reading progress.
 *
 * Every decision belongs to [ReadthroughPolicy]; this class reads the pass, asks
 * the policy, and persists the answer. Three honest outcomes, never a silent
 * success: `Recorded`, `Finished`, or `Refused` (an unknown pass, a finished or
 * abandoned one, a nonsense moment).
 *
 * The journal is APPEND-ONLY here: a record is never rewritten, and the pass
 * keeps the unit it was observed in.
 */
class ReadingProgressRecorder(private val dao: AudiobookDao) {

    sealed interface Result {
        data class Recorded(val readthrough: Readthrough) : Result
        data class Finished(val readthrough: Readthrough) : Result
        data class Refused(val reason: String) : Result
    }

    suspend fun record(readthroughId: String, at: Long, value: Int): Result {
        val pass = load(readthroughId) ?: return Result.Refused(REASON_UNKNOWN_PASS)
        val next = ReadthroughPolicy.recordProgress(pass, at, value)
            ?: return Result.Refused(REASON_NOT_OPEN)
        persist(next)
        return Result.Recorded(next)
    }

    suspend fun finish(readthroughId: String, at: Long): Result {
        val pass = load(readthroughId) ?: return Result.Refused(REASON_UNKNOWN_PASS)
        val next = ReadthroughPolicy.finish(pass, at)
            ?: return Result.Refused(REASON_NOT_OPEN)
        persist(next)
        return Result.Finished(next)
    }

    /**
     * A re-read: a NEW pass with a fresh journal, while the finished one stays
     * exactly as it was — history is never rewritten.
     */
    suspend fun restart(readthroughId: String, newId: String, startedAt: Long): Result {
        val previous = load(readthroughId) ?: return Result.Refused(REASON_UNKNOWN_PASS)
        val next = ReadthroughPolicy.restart(previous, newId, startedAt)
            ?: return Result.Refused(REASON_NOT_OPEN)
        persist(next)
        return Result.Recorded(next)
    }

    /** Every pass of one Work, as the progress surface reads it. */
    suspend fun passesOfWork(workId: String): List<Readthrough> =
        dao.readthroughsForWork(workId).mapNotNull { with(ReadthroughMapping) { it.toModelOrNull() } }

    private suspend fun load(readthroughId: String): Readthrough? =
        dao.readthroughById(readthroughId)?.let { with(ReadthroughMapping) { it.toModelOrNull() } }

    private suspend fun persist(pass: Readthrough) {
        with(ReadthroughMapping) { dao.upsertReadthrough(pass.toEntity()) }
    }

    companion object {
        const val REASON_UNKNOWN_PASS = "unknown-readthrough"
        const val REASON_NOT_OPEN = "readthrough-not-open"
    }
}
