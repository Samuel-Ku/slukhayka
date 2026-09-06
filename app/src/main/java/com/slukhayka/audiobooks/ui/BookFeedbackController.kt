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
    val reviewCreatedAt: Long? = null, val narrationCreatedAt: Long? = null,
    val stored: BookFeedbackDraft = BookFeedbackDraft()
)

/** One editor for both entry points. Each verdict retains its Work/Edition identity. */
internal class BookFeedbackController(
    private val scope: CoroutineScope,
    private val local: BookFeedbackStore,
    private val findBook: suspend (String) -> BookRow?,
    private val findEdition: suspend (String) -> String?,
    private val identity: suspend () -> ListenerProfile?,
    private val reviews: ListenerReviewsStore?,
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
                        ownReview = reviews?.getReviews(workId)?.firstOrNull { it.uid == uid }
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
            val accepted = try {
                withTimeoutOrNull(15_000) {
                    withContext(Dispatchers.IO) {
                        val profile = identity() ?: return@withContext false
                        val now = System.currentTimeMillis()
                        val bookAccepted = if (draft.bookRating in 1..5 &&
                            (draft.bookRating != current.stored.bookRating || draft.body.trim() != current.stored.body.trim())) {
                            reviews?.enqueueReview(ListenerReview(current.workId, profile.uid,
                                profile.nickname.ifBlank { profile.uid }, draft.bookRating,
                                draft.body.trim().takeIf { it.isNotEmpty() }, current.narrator.takeIf { it.isNotBlank() },
                                current.reviewCreatedAt ?: now, if (current.reviewCreatedAt != null) now else null)) is ReviewWriteReceipt.Queued
                        } else true
                        val narrationAccepted = if (draft.narrationRating in 1..5 && draft.narrationRating != current.stored.narrationRating) {
                            current.editionId?.let { edition -> narrations?.putRating(NarrationRating(
                                current.workId, profile.uid, edition, draft.narrationRating,
                                current.narrationCreatedAt ?: now, if (current.narrationCreatedAt != null) now else null)) } == true
                        } else true
                        bookAccepted && narrationAccepted
                    }
                } == true
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { false }
            if (_state.value?.bookId == current.bookId) {
                _state.value = current.copy(saving = false, failed = !accepted, accepted = accepted)
                if (accepted) {
                    local.clearDraft(current.bookId)
                    local.dismiss(current.bookId)
                    onAccepted(current.workId)
                }
            }
        }
    }
}
