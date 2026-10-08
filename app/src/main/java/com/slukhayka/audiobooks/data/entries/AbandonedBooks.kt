package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.ReadthroughMapping
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * spec-52 US28 / #1174 — the write path of «покинути книгу».
 *
 * The DECISION belongs to [ReadthroughPolicy] and [AbandonBookPolicy]; this
 * class reads the book's evidence, asks them, and persists the answer. Two
 * honest outcomes, never a silent success: `Changed` or `Refused`.
 *
 * The mark lives on the book's AUDIO Readthrough, and every write goes through
 * [AUDIO_PASS_ID_PREFIX] — the deterministic id the 45->46 backfill used. That
 * is what makes the action an UPSERT: imports that never had a pass gain one,
 * and a repeat call rewrites the same row instead of forking a second pass.
 * The live position is never copied into the row (ADR-0046 §3): the pass
 * carries only its state and the units it already knew.
 */
class AbandonedBooks(
    private val dao: AudiobookDao,
    private val listeningState: ListeningStateStore
) {

    sealed interface Result {
        /** The pass now carries [state] — ABANDONED, or the restored one. */
        data class Changed(val state: ReadingState) : Result

        data class Refused(val reason: String) : Result
    }

    /**
     * Marks the book abandoned: only a book with real progress (a Listening
     * State row) that is not finished may be marked. The pass is created when
     * the book never had one.
     */
    suspend fun abandon(bookId: String): Result {
        val entry = dao.libraryEntryById(bookId) ?: return Result.Refused(REASON_UNKNOWN_BOOK)
        val progress = listeningState.getProgressSync(bookId)
            ?: return Result.Refused(REASON_NO_PROGRESS)
        if (progress.isCompleted) return Result.Refused(REASON_FINISHED)

        val stored = storedPass(bookId)
        if (stored is StoredPass.Unreadable) return Result.Refused(REASON_UNREADABLE_PASS)
        val pass = (stored as? StoredPass.Found)?.pass ?: run {
            val editionId = listeningState.editionIdFor(bookId)
                ?: return Result.Refused(REASON_UNKNOWN_BOOK)
            freshPass(bookId, entry, editionId)
        }
        val marked = ReadthroughPolicy.abandon(pass) ?: return Result.Refused(REASON_FINISHED)
        persist(marked)
        return Result.Changed(marked.state)
    }

    /**
     * Takes the mark back. The pass returns to the state its evidence proves —
     * IN_PROGRESS while a Listening State row exists, PLANNED otherwise — so a
     * cancelled abandon leaves the book exactly as it was (the position was
     * never touched).
     */
    suspend fun cancel(bookId: String): Result {
        val stored = storedPass(bookId)
        if (stored is StoredPass.Unreadable) return Result.Refused(REASON_UNREADABLE_PASS)
        val pass = (stored as? StoredPass.Found)?.pass
            ?: return Result.Refused(REASON_NOT_ABANDONED)
        val hasProgress = listeningState.getProgressSync(bookId) != null
        val restored = ReadthroughPolicy.reopen(pass, hasProgress)
            ?: return Result.Refused(REASON_NOT_ABANDONED)
        persist(restored)
        return Result.Changed(restored.state)
    }

    /**
     * The books carrying the mark right now — one flow for both surfaces (the
     * library badge and the book page's cancel).
     */
    fun observeAbandonedBookIds(): Flow<Set<String>> =
        dao.observeAbandonedAudioPasses().map { it.toSet() }

    /** The stored pass of this book, told apart from an unreadable one. */
    private suspend fun storedPass(bookId: String): StoredPass {
        val row = dao.readthroughById(readthroughId(bookId)) ?: return StoredPass.Missing
        val pass = with(ReadthroughMapping) { row.toModelOrNull() }
        return if (pass != null && pass.format == ReadingFormat.AUDIO) {
            StoredPass.Found(pass)
        } else {
            // The strict mapping refused the row: repairing it here would
            // silently rewrite a pass the app cannot classify (ADR-0014).
            StoredPass.Unreadable
        }
    }

    /**
     * The AUDIO pass of a book that never had one — imports after the 45->46
     * backfill write no Readthrough (the manual add is the other writer). The
     * shape repeats the backfill's: the deterministic id, the ENTRY's own
     * createdAt as the moment the pass began, the Edition the Listening State
     * names, and ZERO units — 0 stays 0 instead of becoming a made-up date or
     * an invented second position.
     */
    private fun freshPass(bookId: String, entry: LibraryEntryEntity, editionId: String): Readthrough =
        Readthrough(
            id = readthroughId(bookId),
            libraryEntryId = bookId,
            workId = entry.workId,
            format = ReadingFormat.AUDIO,
            state = ReadingState.IN_PROGRESS,
            startedAt = entry.createdAt,
            editionId = editionId,
            units = ReadingUnits(ReadingUnit.SECONDS, 0)
        )

    private suspend fun persist(pass: Readthrough) {
        with(ReadthroughMapping) { dao.upsertReadthrough(pass.toEntity()) }
    }

    /** The three answers a stored-pass lookup has: found, absent, unreadable. */
    private sealed interface StoredPass {
        data class Found(val pass: Readthrough) : StoredPass
        data object Missing : StoredPass
        data object Unreadable : StoredPass
    }

    companion object {
        /**
         * The deterministic id of a book's AUDIO pass — `rt-audio-<entryId>`,
         * exactly the `'rt-audio-' || e.id` MIGRATION_45_46 backfilled. One id
         * per book is what keeps the action an upsert.
         */
        const val AUDIO_PASS_ID_PREFIX = "rt-audio-"

        fun readthroughId(bookId: String): String = AUDIO_PASS_ID_PREFIX + bookId

        const val REASON_UNKNOWN_BOOK = "unknown-book"
        const val REASON_NO_PROGRESS = "no-listening-progress"
        const val REASON_FINISHED = "book-finished"
        const val REASON_NOT_ABANDONED = "not-abandoned"
        const val REASON_UNREADABLE_PASS = "unreadable-pass"
    }
}
