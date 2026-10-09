package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.ReadthroughEntity
import com.slukhayka.audiobooks.data.db.ReadthroughMapping
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.listening.bookProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * spec-52 US28 / #1174 — the write path of «покинути книгу».
 *
 * The DECISION belongs to [ReadthroughPolicy] and [AbandonBookPolicy]; this
 * class reads the book's evidence, asks them, and persists the answer. Two
 * honest outcomes, never a silent success: `Changed` or `Refused`.
 *
 * #1174 (друга смуга) adds the third move — [finish], the completion that
 * takes the mark away and captures the fact the award needs before it does.
 *
 * The mark lives on the book's AUDIO Readthrough, and a pass the mark has to
 * bring into being is written under [readthroughId] — the deterministic id the
 * 45->46 backfill used, so a repeat call rewrites the same row instead of
 * forking a second pass. The live position is never copied into the row
 * (ADR-0046 §3): the pass carries only its state and the units it already knew.
 */
class AbandonedBooks(
    private val dao: AudiobookDao,
    private val listeningState: ListeningStateStore,
    private val undo: AbandonUndo,
    /**
     * #1174 (друга смуга) — the moment a completed book's pass is stamped with.
     * Injectable for the same reason the store's clock is: a test of the
     * completion write must not depend on when it runs.
     */
    private val now: () -> Long = System::currentTimeMillis
) {

    sealed interface Result {
        /**
         * The pass now carries [state] — or NO pass at all ([state] is null)
         * when the cancel removed the one the mark itself had created.
         */
        data class Changed(val state: ReadingState?) : Result

        data class Refused(val reason: String) : Result
    }

    /**
     * Marks the book abandoned: only a book with real progress (a Listening
     * State row) that is not finished may be marked — by the SAME completion
     * rule the library card and the book page use, never by a weaker local one.
     * The pass is created when the book never had one, and the note of what the
     * mark took away is left for the cancel.
     */
    suspend fun abandon(bookId: String): Result {
        val entry = dao.libraryEntryById(bookId) ?: return Result.Refused(REASON_UNKNOWN_BOOK)
        val progress = listeningState.getProgressSync(bookId)
            ?: return Result.Refused(REASON_NO_PROGRESS)
        val progressFacts = bookProgress(
            chapters = dao.getChaptersListForBook(bookId),
            progress = progress,
            bookTotalDurationSeconds = dao.getAudiobookById(bookId)?.totalDurationSeconds ?: 0L
        )
        if (progressFacts.isFinished) return Result.Refused(REASON_FINISHED)

        val stored = openPass(bookId)
        if (stored is StoredPass.Unreadable) return Result.Refused(REASON_UNREADABLE_PASS)
        if (stored is StoredPass.Finished) return Result.Refused(REASON_FINISHED)
        val existing = (stored as? StoredPass.Found)?.pass

        val pass = existing ?: run {
            // The pass the mark brings into being names the EXISTING Edition
            // (ADR-0046 §3): a book without one has no audio pass to write.
            val editionId = listeningState.editionIdFor(bookId)
                ?: return Result.Refused(REASON_NO_EDITION)
            freshPass(bookId, entry, editionId) ?: return Result.Refused(REASON_NO_MOMENT)
        }
        val alreadyMarked = pass.state == ReadingState.ABANDONED
        val marked = ReadthroughPolicy.abandon(pass) ?: return Result.Refused(REASON_FINISHED)
        // The note goes FIRST: a process death between the two writes then
        // leaves a note without a mark (harmless, overwritten by the next one)
        // instead of a mark whose undo would have to guess. A repeat mark must
        // not overwrite the note of the FIRST one — that note still describes
        // what the pass was before any mark.
        if (!alreadyMarked) {
            undo.remember(
                bookId,
                AbandonUndo.BeforeMark(existed = existing != null, state = existing?.state)
            )
        }
        persist(marked)
        return Result.Changed(marked.state)
    }

    /**
     * Takes the mark back. A pass the mark itself created leaves with it — the
     * book is exactly as it was, with no phantom readthrough on «Мій рік». A
     * pass that existed before returns to the state the note remembers, and
     * only a note that is gone falls back to the state the evidence proves.
     * Neither path touches the live position.
     */
    suspend fun cancel(bookId: String): Result {
        val stored = abandonedPass(bookId)
        if (stored is StoredPass.Unreadable) return Result.Refused(REASON_UNREADABLE_PASS)
        val pass = (stored as? StoredPass.Found)?.pass
            ?: return Result.Refused(REASON_NOT_ABANDONED)

        val before = undo.recall(bookId)
        undo.forget(bookId)
        if (before != null && !before.existed) {
            dao.deleteReadthrough(pass.id)
            return Result.Changed(state = null)
        }
        val restored = ReadthroughPolicy.restore(
            readthrough = pass,
            previousState = before?.state,
            hasListeningProgress = listeningState.getProgressSync(bookId) != null
        ) ?: return Result.Refused(REASON_NOT_ABANDONED)
        persist(restored)
        return Result.Changed(restored.state)
    }

    /**
     * #1174 (друга смуга) — завершення книги знімає позначку: a finished book
     * is not an abandoned one, so the pass the mark sits on goes back to
     * FINISHED through the SAME policy the reading side uses
     * ([ReadthroughPolicy.finish]) — the state the mark overwrote is not
     * invented here (ADR-0014), and a pass that already reads FINISHED is
     * history and is refused by that policy.
     *
     * [onFinishedAfterAbandon] runs BEFORE the row is rewritten and ONLY while
     * the mark is still on it. That order is the whole point: this is the last
     * moment the fact «завершив після покинутого» is readable at all, and
     * «Друге дихання» has to see it — after the write the pass reads FINISHED
     * and nothing says it was ever abandoned. The callback is therefore a
     * CAPTURE, not a notification, and it is suspending so the capture can be
     * durable before the row it talks about stops proving it.
     *
     * Called from the two doors where THIS device declares a book finished: the
     * player's own end-of-book event (which passes the capture) and the manual
     * «Прослухано» mark (which does not — ADR-0060 reads completion for the
     * awards as the end-of-book event, never as a hand-set flag).
     *
     * @return true when the book carried the mark and the pass now reads
     *   FINISHED; false when there was nothing to take away — the ordinary
     *   outcome of finishing a book nobody abandoned, and never an error.
     */
    suspend fun finish(bookId: String, onFinishedAfterAbandon: suspend (String) -> Unit = {}): Boolean {
        val stored = abandonedPass(bookId)
        // A row the strict mapping cannot read is left alone (ADR-0014), and a
        // book without the mark has nothing to clear: the completion itself
        // belongs to its own door and is not refused here.
        val pass = (stored as? StoredPass.Found)?.pass ?: return false
        val finished = ReadthroughPolicy.finish(pass, at = now()) ?: return false
        onFinishedAfterAbandon(bookId)
        persist(finished)
        // The note described the live mark; the mark is gone (AbandonUndo:
        // «the cancel consumed it, or the mark is gone»). Forgetting it after
        // the write keeps the harmless direction on a process death — a note
        // without a mark is overwritten by the next one, while a mark without a
        // note would make the cancel guess.
        undo.forget(bookId)
        return true
    }

    /**
     * The books carrying the mark right now — one flow for both surfaces (the
     * library badge and the book page's cancel).
     */
    fun observeAbandonedBookIds(): Flow<Set<String>> =
        dao.observeAbandonedAudioPasses().map { it.toSet() }

    /**
     * The book's OPEN audio pass — the one a mark belongs on. The deterministic
     * [readthroughId] is the CREATION path (it is what the backfill wrote); an
     * audio pass another writer made under its own id is found here too, so a
     * mark never shadows it with a second pass. Only history (a FINISHED pass)
     * is told apart from no pass at all.
     */
    private suspend fun openPass(bookId: String): StoredPass {
        val rows = dao.readthroughsForEntry(bookId).filter { it.format == AUDIO_FORMAT }
        val deterministicId = readthroughId(bookId)
        val open = rows.firstOrNull { it.id == deterministicId && it.state != FINISHED_STATE }
            ?: rows.firstOrNull { it.id != deterministicId && it.state != FINISHED_STATE }
        if (open == null) {
            return if (rows.any { it.state == FINISHED_STATE }) StoredPass.Finished else StoredPass.Missing
        }
        return decode(open)
    }

    /** The pass the live mark sits on — the book's ABANDONED audio pass. */
    private suspend fun abandonedPass(bookId: String): StoredPass {
        val row = dao.readthroughsForEntry(bookId)
            .firstOrNull { it.format == AUDIO_FORMAT && it.state == ABANDONED_STATE }
            ?: return StoredPass.Missing
        return decode(row)
    }

    /**
     * The strict mapping refused the row: rewriting it here would silently
     * replace a pass the app cannot classify (ADR-0014).
     */
    private fun decode(row: ReadthroughEntity): StoredPass {
        val pass = with(ReadthroughMapping) { row.toModelOrNull() }
        return if (pass != null && pass.format == ReadingFormat.AUDIO) {
            StoredPass.Found(pass)
        } else {
            StoredPass.Unreadable
        }
    }

    /**
     * The AUDIO pass of a book that never had one — imports after the 45->46
     * backfill write no Readthrough (the manual add is the other writer). The
     * shape repeats the backfill's: the deterministic id, the ENTRY's own
     * createdAt as the moment the pass began, the Edition the Listening State
     * names ([editionId], already resolved by the caller), and ZERO units — 0
     * stays 0 instead of becoming an invented second position. An entry that
     * carries no moment at all yields null rather than a guessed date
     * (ADR-0014), which the caller refuses as [REASON_NO_MOMENT].
     */
    private fun freshPass(
        bookId: String,
        entry: LibraryEntryEntity,
        editionId: String
    ): Readthrough? = ReadthroughPolicy.start(
        id = readthroughId(bookId),
        libraryEntryId = bookId,
        workId = entry.workId,
        format = ReadingFormat.AUDIO,
        startedAt = entry.createdAt,
        editionId = editionId
    )

    private suspend fun persist(pass: Readthrough) {
        with(ReadthroughMapping) { dao.upsertReadthrough(pass.toEntity()) }
    }

    /** What a pass lookup found: a readable pass, nothing, history, or a row the app cannot read. */
    private sealed interface StoredPass {
        data class Found(val pass: Readthrough) : StoredPass
        data object Missing : StoredPass
        data object Finished : StoredPass
        data object Unreadable : StoredPass
    }

    companion object {
        /**
         * The deterministic id of a book's AUDIO pass — `rt-audio-<entryId>`,
         * exactly the `'rt-audio-' || e.id` MIGRATION_45_46 backfilled. One id
         * per book is what keeps the mark an upsert.
         */
        const val AUDIO_PASS_ID_PREFIX = "rt-audio-"

        fun readthroughId(bookId: String): String = AUDIO_PASS_ID_PREFIX + bookId

        private const val AUDIO_FORMAT = "AUDIO"
        private const val ABANDONED_STATE = "ABANDONED"
        private const val FINISHED_STATE = "FINISHED"

        const val REASON_UNKNOWN_BOOK = "unknown-book"
        const val REASON_NO_PROGRESS = "no-listening-progress"
        const val REASON_FINISHED = "book-finished"
        const val REASON_NOT_ABANDONED = "not-abandoned"
        const val REASON_UNREADABLE_PASS = "unreadable-pass"

        /**
         * The library entry carries no moment to begin the pass with — the
         * ONLY reason `ReadthroughPolicy.start` refuses a pass this module
         * builds (its other guards — blank ids, the Edition rule — are settled
         * before the call).
         */
        const val REASON_NO_MOMENT = "no-moment"

        /**
         * The book has no Edition for the pass to name (ADR-0046 §3): a mark
         * cannot invent the rendition it is about.
         */
        const val REASON_NO_EDITION = "no-edition"
    }
}
