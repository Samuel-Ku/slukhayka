package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Spec-620 (#626) — how one delete ended. [QUEUED] is local acceptance only. */
enum class ReviewDeleteResult { DELETED, QUEUED, FAILED }

/** Spec-620 (#626) — one ordered delete event, scoped to its document. */
data class ReviewDeleteEvent(
    val workId: String,
    val documentId: String,
    val generation: Long,
    val result: ReviewDeleteResult
)

/**
 * Spec-620 (#623/#626) — the Work-scoped state one screen reads.
 *
 * [confirmed] is the last server truth, [pending] the local save acceptance,
 * [deleting] the documents locally accepted for deletion (already gone from
 * [visible]), [failedSave]/[failedDelete] the exact payloads a retry may
 * resend. [uid] is the listener epoch: a different listener keeps the public
 * [confirmed] truth but never inherits the previous listener's overlays.
 */
data class ListenerReviewState(
    /** The Work this state belongs to; empty before the module is opened. */
    val workId: String = "",
    /** The listener epoch this state belongs to; a uid change starts a new one. */
    val uid: String = "",
    val confirmed: List<ListenerReview> = emptyList(),
    /** Local save acceptances awaiting a backend verdict, keyed by document id. */
    val pending: Map<String, ListenerReview> = emptyMap(),
    /** Document ids locally accepted for deletion (removed from [visible] now). */
    val deleting: Set<String> = emptySet(),
    /** The last FAILED save per document id — the exact payload a retry resends. */
    val failedSave: Map<String, ListenerReview> = emptyMap(),
    /** Document ids whose last FAILED mutation was a delete. */
    val failedDelete: Set<String> = emptySet(),
    /** True when the last read failed: [confirmed] is the last good snapshot. */
    val readFailed: Boolean = false
) {
    /** Confirmed server truth plus the pending cards, minus locally deleted ones. */
    val visible: List<ListenerReview>
        get() = (
            confirmed.filterNot { documentIdOf(it) in pending || documentIdOf(it) in deleting } +
                pending.values
            ).sortedByDescending { it.createdAt }

    /** Nothing to retry when both failure buckets are empty. */
    val hasFailedMutation: Boolean get() = failedSave.isNotEmpty() || failedDelete.isNotEmpty()

    private fun documentIdOf(review: ListenerReview): String =
        ListenerReviewCodec.documentId(review.workId, review.uid)
}

/**
 * Spec-620 (#623/#626) — ONE Work-scoped lifecycle module for listener
 * reviews: reading, local acceptance, the pending overlay, save/edit/delete
 * ordering, retry and the backend acknowledgement all live here instead of
 * being spread across `MainViewModel`, the book page and the Firestore store.
 *
 * The rules it owns:
 * - state is scoped to one Work — opening another Work drops the previous
 *   reviews, pending cards, failures and in-flight work;
 * - a different listener uid is a new EPOCH: the public confirmed truth stays,
 *   every private overlay and every late callback of the old uid dies;
 * - save, edit and delete of one document share ONE ordering gate, so a newer
 *   mutation always supersedes a late acknowledgement of an older one;
 * - delete has its own local acceptance (the card is gone immediately) and a
 *   separate backend verdict; an offline accepted delete never blocks the
 *   editor, and a FAILED delete restores the confirmed card and stays visible;
 * - retry resends the EXACT failed payload and never a newer local draft;
 * - a failed read keeps the last confirmed snapshot; a genuine empty clears it;
 * - cancellation of the awaiting coroutine never revokes a write that was
 *   already accepted locally — Firestore local persistence stays the only
 *   durable queue, and no second outbox is introduced.
 *
 * The module depends only on the [ListenerReviewsStore] seam and a clock, so
 * the whole state machine is pinned by plain JVM tests over an in-memory fake.
 */
class ListenerReviewLifecycle(
    private val store: ListenerReviewsStore?,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val _state = MutableStateFlow(ListenerReviewState())
    val state: StateFlow<ListenerReviewState> = _state.asStateFlow()

    private val _results = MutableSharedFlow<ReviewSaveEvent>(extraBufferCapacity = 16)
    val results: SharedFlow<ReviewSaveEvent> = _results.asSharedFlow()

    private val _deleteResults = MutableSharedFlow<ReviewDeleteEvent>(extraBufferCapacity = 16)
    val deleteResults: SharedFlow<ReviewDeleteEvent> = _deleteResults.asSharedFlow()

    private val submissions = ReviewSubmissionGate()
    private val loads = ReviewLoadGate()

    /** Bumped on every Work/uid switch: late callbacks compare against it. */
    private val epoch = AtomicLong(0)

    /**
     * Scopes the module to one Work and one listener.
     *
     * A different Work starts from an empty state. A different uid keeps the
     * PUBLIC confirmed snapshot but drops every private overlay of the old
     * listener (pending, deleting, failed payloads) — the previous listener's
     * private state never belongs to the new profile.
     */
    fun open(workId: String, uid: String = "") {
        val current = _state.value
        when {
            current.workId != workId -> {
                epoch.incrementAndGet()
                _state.value = ListenerReviewState(workId = workId, uid = uid)
            }
            current.uid != uid -> {
                epoch.incrementAndGet()
                _state.value = current.copy(
                    uid = uid,
                    pending = emptyMap(),
                    deleting = emptySet(),
                    failedSave = emptyMap(),
                    failedDelete = emptySet()
                )
            }
        }
    }

    /**
     * Re-reads one Work's reviews. A failure keeps the previous confirmed
     * snapshot; a successful empty clears it. Results for a Work that is no
     * longer open (or superseded by a newer read) are dropped.
     */
    suspend fun refresh(workId: String) {
        if (workId != _state.value.workId) return
        val seam = store ?: return
        val request = loads.begin(workId)
        val result = seam.readReviews(workId)
        if (workId != _state.value.workId || !loads.isLatest(request)) return
        when (result) {
            is ReviewReadResult.Data ->
                _state.update { it.copy(confirmed = result.reviews, readFailed = false) }
            ReviewReadResult.Empty ->
                _state.update { it.copy(confirmed = emptyList(), readFailed = false) }
            ReviewReadResult.Failure ->
                // The community is NOT empty — it is unreachable. Keep the
                // last confirmed snapshot and say that it is stale.
                _state.update { it.copy(readFailed = true) }
        }
    }

    /**
     * Creates or edits the listener's review of the open Work.
     *
     * Local acceptance appears as a pending card immediately; the backend
     * acknowledgement later turns it into published or failed. A save after a
     * delete is allowed and creates a fresh review — a review has no tombstone.
     */
    suspend fun save(
        workId: String,
        uid: String,
        nickname: String,
        rating: Int,
        body: String?,
        editionTag: String?,
        editing: ListenerReview?
    ): ReviewSaveResult {
        val documentId = ListenerReviewCodec.documentId(workId, uid)
        if (workId != _state.value.workId) {
            return rejectSave(workId, documentId)
        }
        // The screen may have opened before the profile loaded: the first save
        // establishes the listener epoch instead of being refused.
        if (_state.value.uid != uid) open(workId, uid)
        val seam = store
        if (seam == null || uid.isBlank() || !ListenerReviewLimits.isValidRating(rating)) {
            return rejectSave(workId, documentId)
        }
        val createdAt = editing?.createdAt ?: now()
        val review = ListenerReview(
            workId = workId,
            uid = uid,
            authorName = nickname.ifBlank { uid },
            rating = rating,
            body = body?.trim()?.takeIf { it.isNotEmpty() },
            editionTag = editionTag?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = createdAt,
            editedAt = if (editing != null) now() else null
        )
        return sendSave(review, epoch.get())
    }

    /**
     * Locally accepts the deletion of the listener's own review of the open
     * Work. The card is gone from [visible] immediately — an offline accepted
     * delete does not wait for the network. The backend verdict follows: on
     * success the deletion stands, on failure the confirmed card comes back
     * and the failure stays visible/retryable.
     */
    suspend fun delete(workId: String, uid: String): ReviewDeleteResult {
        if (workId != _state.value.workId) {
            return ReviewDeleteResult.FAILED
        }
        if (_state.value.uid != uid) open(workId, uid)
        val documentId = ListenerReviewCodec.documentId(workId, uid)
        if (store == null) {
            // No Firebase = deleting is unavailable; say so honestly.
            val rejected = submissions.begin(workId, documentId)
            _deleteResults.emit(rejected.deleteEvent(ReviewDeleteResult.FAILED))
            return ReviewDeleteResult.FAILED
        }
        return sendDelete(workId, documentId, epoch.get())
    }

    /**
     * Resends the last FAILED mutation of the open Work — the exact payload,
     * never a newer local draft (a newer save clears the failed bucket).
     *
     * @return the document id that was retried, or null when nothing failed.
     */
    suspend fun retry(workId: String): String? {
        if (workId != _state.value.workId) return null
        val state = _state.value
        val epochAtStart = epoch.get()
        state.failedSave.entries.firstOrNull()?.let { (documentId, review) ->
            if (ListenerReviewCodec.documentId(review.workId, review.uid) != documentId) return null
            sendSave(review, epochAtStart)
            return documentId
        }
        val failedDelete = state.failedDelete.firstOrNull() ?: return null
        sendDelete(workId, failedDelete, epochAtStart)
        return failedDelete
    }

    // ------------------------------------------------------------------
    // Ordered transports — save/edit/delete of one document share the gate
    // ------------------------------------------------------------------

    private suspend fun sendSave(review: ListenerReview, epochAtStart: Long): ReviewSaveResult {
        val seam = store ?: return ReviewSaveResult.FAILED
        val workId = review.workId
        val documentId = ListenerReviewCodec.documentId(workId, review.uid)
        val submission = submissions.begin(workId, documentId)
        // Local acceptance FIRST — the listener sees their card instantly. A
        // save supersedes any earlier failure and any pending delete.
        _state.update {
            it.copy(
                pending = it.pending + (documentId to review),
                deleting = it.deleting - documentId,
                failedSave = it.failedSave - documentId,
                failedDelete = it.failedDelete - documentId
            )
        }

        val receipt = try {
            seam.enqueueReview(review)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReviewWriteReceipt.Rejected
        }

        var outcome = ReviewSaveResult.FAILED
        followReviewWrite(
            receipt = receipt,
            onVisibleResult = { result ->
                if (!inScope(workId, epochAtStart) || !submissions.isLatest(submission)) {
                    return@followReviewWrite
                }
                outcome = result
                when (result) {
                    ReviewSaveResult.QUEUED ->
                        _results.emit(submission.event(result))
                    ReviewSaveResult.PUBLISHED -> {
                        _state.update { current ->
                            current.copy(
                                pending = current.pending - documentId,
                                confirmed = (
                                    current.confirmed.filterNot {
                                        ListenerReviewCodec.documentId(it.workId, it.uid) == documentId
                                    } + review
                                    ).sortedByDescending { it.createdAt },
                                failedSave = current.failedSave - documentId
                            )
                        }
                        _results.emit(submission.event(result))
                    }
                    ReviewSaveResult.FAILED -> {
                        // The payload is kept verbatim so retry is honest.
                        _state.update { current ->
                            current.copy(
                                pending = current.pending - documentId,
                                failedSave = current.failedSave + (documentId to review)
                            )
                        }
                        _results.emit(submission.event(result))
                    }
                }
            },
            onRemoteResult = { /* the verdict arrives again via onVisibleResult */ }
        )
        return outcome
    }

    private suspend fun sendDelete(
        workId: String,
        documentId: String,
        epochAtStart: Long
    ): ReviewDeleteResult {
        val seam = store ?: return ReviewDeleteResult.FAILED
        val submission = submissions.begin(workId, documentId)
        val restore = _state.value.confirmed.firstOrNull {
            ListenerReviewCodec.documentId(it.workId, it.uid) == documentId
        }
        // LOCAL ACCEPTANCE: the card disappears now; the network verdict later.
        _state.update {
            it.copy(
                confirmed = it.confirmed.filterNot { row ->
                    ListenerReviewCodec.documentId(row.workId, row.uid) == documentId
                },
                pending = it.pending - documentId,
                deleting = it.deleting + documentId,
                failedSave = it.failedSave - documentId,
                failedDelete = it.failedDelete - documentId
            )
        }
        _deleteResults.emit(submission.deleteEvent(ReviewDeleteResult.QUEUED))

        val receipt = try {
            seam.enqueueDelete(documentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReviewDeleteReceipt.Rejected
        }

        val succeeded = when (receipt) {
            ReviewDeleteReceipt.Rejected -> false
            is ReviewDeleteReceipt.Queued -> receipt.awaitRemote()
        }
        if (inScope(workId, epochAtStart) && submissions.isLatest(submission)) {
            _state.update { current ->
                current.copy(
                    deleting = current.deleting - documentId,
                    failedDelete = if (succeeded) {
                        current.failedDelete - documentId
                    } else {
                        current.failedDelete + documentId
                    },
                    // A failed delete must not hide the review: the last
                    // confirmed card comes back until the listener retries.
                    confirmed = if (succeeded) {
                        current.confirmed
                    } else {
                        restoreInto(current.confirmed, restore)
                    }
                )
            }
            _deleteResults.emit(
                submission.deleteEvent(
                    if (succeeded) ReviewDeleteResult.DELETED else ReviewDeleteResult.FAILED
                )
            )
        }
        return if (succeeded) ReviewDeleteResult.DELETED else ReviewDeleteResult.FAILED
    }

    /** A late callback applies only while its Work AND listener epoch still hold. */
    private fun inScope(workId: String, epochAtStart: Long): Boolean =
        epoch.get() == epochAtStart && _state.value.workId == workId

    private fun restoreInto(
        confirmed: List<ListenerReview>,
        restore: ListenerReview?
    ): List<ListenerReview> {
        if (restore == null) return confirmed
        val documentId = ListenerReviewCodec.documentId(restore.workId, restore.uid)
        return (
            confirmed.filterNot { ListenerReviewCodec.documentId(it.workId, it.uid) == documentId } + restore
            ).sortedByDescending { it.createdAt }
    }

    private suspend fun rejectSave(workId: String, documentId: String): ReviewSaveResult {
        // Honest local rejection: announced, but never a fabricated card.
        val rejected = submissions.begin(workId, documentId)
        _results.emit(rejected.event(ReviewSaveResult.FAILED))
        return ReviewSaveResult.FAILED
    }
}
