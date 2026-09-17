package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.identity.ListenerProfile
import com.slukhayka.audiobooks.data.reviews.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class BookFeedbackState(
    val bookId: String, val workId: String, val editionId: String?, val title: String,
    val narrator: String, val draft: BookFeedbackDraft, val automatic: Boolean,
    val loading: Boolean = true, val saving: Boolean = false,
    val failed: Boolean = false, val accepted: Boolean = false,
    /**
     * Spec-620 (#627) — per-verdict acceptance. A retry sends ONLY the part
     * that was not accepted, so an accepted Listener Review is never created a
     * second time (and never with a different date).
     */
    val reviewAccepted: Boolean = false, val narrationAccepted: Boolean = false,
    val reviewCreatedAt: Long? = null, val narrationCreatedAt: Long? = null,
    val stored: BookFeedbackDraft = BookFeedbackDraft()
)

/**
 * One editor for both entry points. Each verdict retains its Work/Edition identity.
 *
 * Spec-620 (#627) — the LISTENER REVIEW half now goes through the same
 * [ListenerReviewLifecycle] module the book page uses, so both editors share
 * one lifecycle interface; the Narration Rating stays a separate domain
 * verdict sent directly to its own store. Draft, the automatic completion
 * prompt, dismiss and the partial-result bookkeeping stay here by decision.
 *
 * The controller owns its OWN lifecycle instance: it is Work-scoped state, and
 * the completion editor must not clobber the book page's open Work.
 */
internal class BookFeedbackController(
    private val scope: CoroutineScope,
    private val local: BookFeedbackStore,
    private val findBook: suspend (String) -> BookRow?,
    private val findEdition: suspend (String) -> String?,
    private val identity: suspend () -> ListenerProfile?,
    private val reviewLifecycle: ListenerReviewLifecycle?,
    private val narrations: NarrationRatingsStore?,
    private val onAccepted: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow<BookFeedbackState?>(null)
    val state = _state.asStateFlow()
    private var openJob: Job? = null

    fun open(bookId: String, automatic: Boolean = false) {
        if (_state.value != null || openJob?.isActive == true) return
        openJob = scope.launch {
            try {
                val book = withContext(Dispatchers.IO) { findBook(bookId) }
                if (book == null) { if (automatic) local.dismiss(bookId); return@launch }
                val editionId = withContext(Dispatchers.IO) { findEdition(bookId) }
                val workId = reviewWorkIdFor(book.id, book.workId)
                val savedDraft = local.draft(bookId)
                val initial = BookFeedbackState(bookId, workId, editionId, book.title, book.narrator,
                    savedDraft ?: BookFeedbackDraft(), automatic)
                if (!automatic) _state.value = initial
                var ownReview: ListenerReview? = null
                var ownNarration: NarrationRating? = null
                try { withTimeoutOrNull(5_000) {
                    withContext(Dispatchers.IO) {
                        val uid = identity()?.uid ?: return@withContext
                        // The review is read through the ONE lifecycle module.
                        reviewLifecycle?.let { lifecycle ->
                            lifecycle.open(workId, uid)
                            lifecycle.refresh(workId)
                            ownReview = lifecycle.state.value.confirmed.firstOrNull { it.uid == uid }
                        }
                        ownNarration = narrations?.getForWork(workId)?.firstOrNull { it.uid == uid && it.editionId == editionId }
                    }
                }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { /* A local draft remains editable without a profile. */ }
                if (!automatic && _state.value?.bookId != bookId) return@launch
                if (automatic && savedDraft == null && ownReview != null && ownNarration != null) {
                    openJob = null
                    local.dismiss(bookId)
                    _state.value = null
                } else {
                    _state.value = initial.copy(loading = false,
                        draft = savedDraft ?: BookFeedbackDraft(ownReview?.rating ?: 0, ownNarration?.rating ?: 0, ownReview?.body.orEmpty()),
                        reviewCreatedAt = ownReview?.createdAt, narrationCreatedAt = ownNarration?.createdAt,
                        stored = BookFeedbackDraft(ownReview?.rating ?: 0, ownNarration?.rating ?: 0, ownReview?.body.orEmpty()))
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                _state.value = _state.value?.copy(loading = false, failed = true)
            }
        }
    }

    fun edit(draft: BookFeedbackDraft) {
        val current = _state.value ?: return
        if (current.loading || current.saving || current.accepted) return
        val bounded = draft.copy(body = draft.body.take(ListenerReviewLimits.MAX_BODY_LEN))
        local.saveDraft(current.bookId, bounded)
        _state.value = current.copy(draft = bounded, failed = false)
    }

    fun dismiss() {
        val current = _state.value ?: return
        if (current.saving) return
        openJob?.cancel()
        openJob = null
        if (!current.loading && !current.accepted) local.saveDraft(current.bookId, current.draft)
        local.dismiss(current.bookId)
        _state.value = null
    }

    fun save() {
        val current = _state.value ?: return
        val draft = current.draft
        if (current.loading || current.saving || current.accepted ||
            (draft.bookRating !in 1..5 && draft.narrationRating !in 1..5) ||
            (draft.body.isNotBlank() && draft.bookRating !in 1..5)) return
        local.saveDraft(current.bookId, draft)
        _state.value = current.copy(saving = true, failed = false)
        scope.launch {
            var reviewAccepted = current.reviewAccepted
            var narrationAccepted = current.narrationAccepted
            try {
                withTimeoutOrNull(15_000) {
                    withContext(Dispatchers.IO) {
                        val profile = identity() ?: return@withContext
                        val now = System.currentTimeMillis()
                        reviewAccepted = sendReviewIfDue(current, draft, profile, now, reviewAccepted)
                        narrationAccepted = sendNarrationIfDue(current, draft, profile, now, narrationAccepted)
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { }
            val accepted = reviewAccepted && narrationAccepted
            // Merge into the LATEST state: a late completion never overwrites a
            // newer local draft and never reopens a closed editor.
            val latest = _state.value
            if (latest?.bookId == current.bookId) {
                _state.value = latest.copy(
                    saving = false,
                    failed = !accepted,
                    accepted = accepted,
                    reviewAccepted = reviewAccepted,
                    narrationAccepted = narrationAccepted
                )
                if (accepted) {
                    local.clearDraft(current.bookId)
                    local.dismiss(current.bookId)
                    onAccepted(current.workId)
                }
            }
        }
    }

    /**
     * Spec-620 (#627) — the review half of the feedback rides the ONE lifecycle
     * module. An already-accepted review is never re-sent, so a retry cannot
     * create it again with a different date; a genuine edit keeps its
     * [BookFeedbackState.reviewCreatedAt] through the `editing` payload.
     *
     * @return true when this part is accepted (or was nothing to send).
     */
    private suspend fun sendReviewIfDue(
        current: BookFeedbackState,
        draft: BookFeedbackDraft,
        profile: ListenerProfile,
        now: Long,
        alreadyAccepted: Boolean
    ): Boolean {
        val due = draft.bookRating in 1..5 &&
            (draft.bookRating != current.stored.bookRating || draft.body.trim() != current.stored.body.trim())
        if (alreadyAccepted || !due) return true
        val lifecycle = reviewLifecycle ?: return false
        lifecycle.open(current.workId, profile.uid)
        val body = draft.body.trim().takeIf { it.isNotEmpty() }
        val editionTag = current.narrator.takeIf { it.isNotBlank() }
        val editing = current.reviewCreatedAt?.let { createdAt ->
            ListenerReview(
                workId = current.workId,
                uid = profile.uid,
                authorName = profile.nickname.ifBlank { profile.uid },
                rating = draft.bookRating,
                body = body,
                editionTag = editionTag,
                createdAt = createdAt,
                editedAt = now
            )
        }
        // Local acceptance only: an offline review is accepted at once and the
        // backend verdict continues on the module scope (a LATE failure must
        // never reopen this closed editor).
        val outcome = lifecycle.enqueueSave(
            workId = current.workId,
            uid = profile.uid,
            nickname = profile.nickname,
            rating = draft.bookRating,
            body = body,
            editionTag = editionTag,
            editing = editing
        )
        return outcome != ReviewSaveResult.FAILED
    }

    /**
     * The Narration Rating is a SEPARATE verdict: it never rides the review
     * lifecycle, and its acceptance does not depend on the review's.
     *
     * @return true when this part is accepted (or was nothing to send).
     */
    private suspend fun sendNarrationIfDue(
        current: BookFeedbackState,
        draft: BookFeedbackDraft,
        profile: ListenerProfile,
        now: Long,
        alreadyAccepted: Boolean
    ): Boolean {
        val due = draft.narrationRating in 1..5 && draft.narrationRating != current.stored.narrationRating
        if (alreadyAccepted || !due) return true
        val edition = current.editionId ?: return false
        return narrations?.putRating(
            NarrationRating(
                current.workId,
                profile.uid,
                edition,
                draft.narrationRating,
                current.narrationCreatedAt ?: now,
                if (current.narrationCreatedAt != null) now else null
            )
        ) == true
    }
}
