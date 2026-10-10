package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Owns transient acknowledgement waiters and post-restart reconciliation.
     * SDK mutation persistence and the independent confirmed-cache producer
     * continue separately from this screen's lifetime.
     */
    private val scope: CoroutineScope? = null,
    private val onAccepted: suspend (ListenerReview) -> Unit = {}
) {

    private val _state = MutableStateFlow(ListenerReviewState())
    val state: StateFlow<ListenerReviewState> = _state.asStateFlow()

    private val _results = MutableSharedFlow<ReviewSaveEvent>(extraBufferCapacity = 16)
    val results: SharedFlow<ReviewSaveEvent> = _results.asSharedFlow()

    private val _deleteResults = MutableSharedFlow<ReviewDeleteEvent>(extraBufferCapacity = 16)
    val deleteResults: SharedFlow<ReviewDeleteEvent> = _deleteResults.asSharedFlow()

    private val submissions = ReviewSubmissionGate()
    /** One local enqueue gate per document, shared by save and delete; never waits for the backend. */
    private val localEnqueues = mutableMapOf<String, Mutex>()
    /** Receipt ownership only: an unaccepted later attempt cannot revoke an earlier accepted ACK. */
    private val acceptedSubmissions = mutableMapOf<String, ReviewSubmission>()
    /** Transient receipt owners only; SDK persistence stays independent of their screen epoch. */
    private val mutationCompletions = mutableSetOf<Job>()
    private val loads = ReviewLoadGate()

    /** Restored SDK writes have no surviving Task; server reads provide their verdict. */
    private data class RecoveredSave(val review: ListenerReview, val submission: ReviewSubmission, val epoch: Long)
    private val recoveredSaves = mutableMapOf<String, RecoveredSave>()
    /** Serializes state, read/submission generations and recovered overlays; never holds SDK I/O. */
    private val transitionLock = Any()
    private var recoveryJob: Job? = null
    private val reconciliationGeneration = AtomicLong(0)

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
    fun open(workId: String, uid: String = "") = synchronized(transitionLock) {
        val current = _state.value
        when {
            current.workId != workId -> {
                epoch.incrementAndGet()
                cancelMutationCompletions()
                cancelRecovery()
                recoveredSaves.clear()
                acceptedSubmissions.clear()
                _state.value = ListenerReviewState(workId = workId, uid = uid)
            }
            current.uid != uid -> {
                epoch.incrementAndGet()
                cancelMutationCompletions()
                cancelRecovery()
                recoveredSaves.clear()
                acceptedSubmissions.clear()
                _state.value = current.copy(
                    uid = uid,
                    pending = emptyMap(),
                    deleting = emptySet(),
                    failedSave = emptyMap(),
                    failedDelete = emptySet()
                )
            }
            else -> Unit
        }
    }

    /**
     * Re-reads one Work's reviews. A failure keeps the previous confirmed
     * snapshot; a successful empty clears it. Results for a Work that is no
     * longer open (or superseded by a newer read) are dropped.
     */
    private data class ReadContext(val request: ReviewLoadRequest, val epoch: Long, val uid: String)
    private data class SaveCommit(val result: ReviewSaveResult, val event: ReviewSaveEvent?)
    private data class DeleteCommit(val result: ReviewDeleteResult, val event: ReviewDeleteEvent?)

    private fun currentRead(context: ReadContext): Boolean =
        inScope(context.request.workId, context.epoch) && _state.value.uid == context.uid && loads.isLatest(context.request)

    suspend fun refresh(workId: String) {
        val seam = store ?: return
        val context = synchronized(transitionLock) {
            if (_state.value.workId != workId) return
            ReadContext(loads.begin(workId), epoch.get(), _state.value.uid)
        }
        val result = seam.readReviews(workId, context.uid)
        applyRead(result, context)
    }

    /** Checks and the entire state/recovery commit share the same lock as open and mutations. */
    private suspend fun applyRead(result: ReviewReadResult, context: ReadContext) {
        val events = mutableListOf<ReviewSaveEvent>()
        var reconcile = false
        synchronized(transitionLock) {
            if (!currentRead(context)) return
            when (result) {
                is ReviewReadResult.Snapshot -> {
                    val current = _state.value
                    val sdkPending = result.pending.filter { it.workId == current.workId && it.uid == current.uid }
                        .associateBy { ListenerReviewCodec.documentId(it.workId, it.uid) }
                    val pending = mutableMapOf<String, ListenerReview>()
                    for ((documentId, review) in sdkPending) {
                        val recovered = recoveredSaves[documentId]
                        // A live locally submitted payload owns its overlay; a cached older SDK value cannot replace it.
                        if (documentId in current.pending && recovered == null) continue
                        pending[documentId] = review
                        if (recovered == null || recovered.review != review) {
                            val submission = submissions.begin(current.workId, documentId)
                            acceptedSubmissions[documentId] = submission
                            recoveredSaves[documentId] = RecoveredSave(review, submission, context.epoch)
                        }
                    }
                    val confirmed = if (!result.authoritative || result.fromCache) {
                        val rows = current.confirmed.associateBy { ListenerReviewCodec.documentId(it.workId, it.uid) }.toMutableMap()
                        result.confirmed.forEach { rows[ListenerReviewCodec.documentId(it.workId, it.uid)] = it }
                        rows.values.sortedByDescending { it.createdAt }
                    } else result.confirmed
                    _state.value = current.copy(confirmed = confirmed, pending = current.pending + pending, readFailed = result.readFailed)
                    if (!result.authoritative || result.fromCache) reconcile = true
                    else for ((documentId, recovery) in recoveredSaves.toMap()) {
                        if (documentId in sdkPending || recovery.epoch != context.epoch || recovery.review.uid != context.uid) continue
                        val remote = if (result.confirmed.any { it == recovery.review }) ReviewRemoteResult.PUBLISHED else ReviewRemoteResult.FAILED
                        finishSaveLocked(recovery.submission, current.workId, documentId, recovery.review, context.epoch, remote)
                            .event?.let(events::add)
                        recoveredSaves.remove(documentId, recovery)
                    }
                }
                is ReviewReadResult.Data -> _state.value = _state.value.copy(confirmed = result.reviews, readFailed = false)
                ReviewReadResult.Empty -> _state.value = _state.value.copy(confirmed = emptyList(), readFailed = false)
                ReviewReadResult.Failure -> _state.value = _state.value.copy(readFailed = true)
            }
        }
        // Event decisions linearize with state under transitionLock; suspendable delivery never holds that lock.
        for (event in events) _results.emit(event)
        if (reconcile) startRecovery(context)
    }

    private fun cancelMutationCompletions() = synchronized(transitionLock) {
        val jobs = mutationCompletions.toList()
        mutationCompletions.clear()
        jobs.forEach { it.cancel() }
    }

    private fun cancelRecovery() = synchronized(transitionLock) {
        reconciliationGeneration.incrementAndGet()
        recoveryJob?.cancel()
        recoveryJob = null
    }

    /** Only transient screen reconciliation; the SDK remains the only persistent mutation queue. */
    private fun startRecovery(read: ReadContext) {
        val workId = read.request.workId
        val uid = read.uid
        val epochAtStart = read.epoch
        val seam = store ?: return
        val recoveryScope = scope ?: return
        val job = synchronized(transitionLock) {
            if (recoveryJob != null || !currentRead(read)) return
            val generation = reconciliationGeneration.get()
            fun stillCurrent(): Boolean = inScope(workId, epochAtStart) && _state.value.uid == uid &&
                reconciliationGeneration.get() == generation
            recoveryScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val ready = seam.awaitPendingWrites()
                    coroutineContext.ensureActive()
                    val context = synchronized(transitionLock) {
                        if (!stillCurrent()) return@launch
                        if (!ready) { _state.value = _state.value.copy(readFailed = true); return@launch }
                        ReadContext(loads.begin(workId), epochAtStart, uid)
                    }
                    val result = seam.readServerReviews(workId, uid)
                    coroutineContext.ensureActive()
                    // Generation and request checks must occur at the commit, not only before applyRead.
                    synchronized(transitionLock) {
                        if (!stillCurrent() || !currentRead(context)) return@launch
                    }
                    if (result is ReviewReadResult.Snapshot && result.authoritative && !result.fromCache) {
                        applyRead(result, context)
                    } else synchronized(transitionLock) {
                        if (stillCurrent() && currentRead(context)) _state.value = _state.value.copy(readFailed = true)
                    }
                } finally {
                    synchronized(transitionLock) {
                        if (recoveryJob === coroutineContext[Job]) recoveryJob = null
                    }
                }
            }.also { recoveryJob = it }
        }
        job.start()
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
    ): ReviewSaveResult = buildAndSend(
        workId, uid, nickname, rating, body, editionTag, editing, awaitRemote = true
    )

    /**
     * Spec-620 (#627) — locally accepts a save and returns at once with
     * [ReviewSaveResult.QUEUED]; the backend verdict then continues on the
     * module's [scope] and is announced through [results]. The completion
     * editor needs local acceptance, never the network round trip, so an
     * offline review is accepted (and the editor closes) immediately.
     */
    suspend fun enqueueSave(
        workId: String,
        uid: String,
        nickname: String,
        rating: Int,
        body: String?,
        editionTag: String?,
        editing: ListenerReview?
    ): ReviewSaveResult = buildAndSend(
        workId, uid, nickname, rating, body, editionTag, editing, awaitRemote = false
    )

    private suspend fun buildAndSend(
        workId: String,
        uid: String,
        nickname: String,
        rating: Int,
        body: String?,
        editionTag: String?,
        editing: ListenerReview?,
        awaitRemote: Boolean
    ): ReviewSaveResult {
        val documentId = ListenerReviewCodec.documentId(workId, uid)
        val prepared = synchronized(transitionLock) {
            if (workId != _state.value.workId || store == null || uid.isBlank() || !ListenerReviewLimits.isValidRating(rating)) null
            else {
                if (_state.value.uid != uid) open(workId, uid)
                val createdAt = editing?.createdAt ?: now()
                ListenerReview(workId = workId, uid = uid, authorName = nickname.ifBlank { uid }, rating = rating,
                    body = body?.trim()?.takeIf { it.isNotEmpty() }, editionTag = editionTag?.trim()?.takeIf { it.isNotEmpty() },
                    createdAt = createdAt, editedAt = if (editing != null) now() else null) to epoch.get()
            }
        } ?: return rejectSave(workId, documentId)
        return sendSave(prepared.first, prepared.second, awaitRemote)
    }

    /**
     * Locally accepts the deletion of the listener's own review of the open
     * Work. The card is gone from [visible] immediately — an offline accepted
     * delete does not wait for the network. The backend verdict follows: on
     * success the deletion stands, on failure the confirmed card comes back
     * and the failure stays visible/retryable.
     */
    suspend fun delete(workId: String, uid: String): ReviewDeleteResult {
        val documentId = ListenerReviewCodec.documentId(workId, uid)
        val epochAtStart = synchronized(transitionLock) {
            if (workId != _state.value.workId) return ReviewDeleteResult.FAILED
            if (_state.value.uid != uid) open(workId, uid)
            epoch.get()
        }
        if (store == null) {
            val rejected = synchronized(transitionLock) { submissions.begin(workId, documentId) }
            _deleteResults.emit(rejected.deleteEvent(ReviewDeleteResult.FAILED))
            return ReviewDeleteResult.FAILED
        }
        return sendDelete(workId, documentId, epochAtStart)
    }

    /**
     * Resends the last FAILED mutation of the open Work — the exact payload,
     * never a newer local draft (a newer save clears the failed bucket).
     *
     * @return the document id that was retried, or null when nothing failed.
     */
    suspend fun retry(workId: String): String? {
        val (state, epochAtStart) = synchronized(transitionLock) {
            if (workId != _state.value.workId) return null
            _state.value to epoch.get()
        }
        state.failedSave.entries.firstOrNull()?.let { (documentId, review) ->
            if (ListenerReviewCodec.documentId(review.workId, review.uid) != documentId) return null
            sendSave(review, epochAtStart, awaitRemote = true)
            return documentId
        }
        val failedDelete = state.failedDelete.firstOrNull() ?: return null
        sendDelete(workId, failedDelete, epochAtStart)
        return failedDelete
    }

    // ------------------------------------------------------------------
    // Ordered transports — save/edit/delete of one document share the gate
    // ------------------------------------------------------------------

    private fun localEnqueueGate(documentId: String): Mutex = synchronized(transitionLock) {
        localEnqueues.getOrPut(documentId) { Mutex() }
    }

    /** Transient local outcomes let suspendable notifications run after the document gate is released. */
    private data class SaveEnqueue(
        val submission: ReviewSubmission,
        val receipt: ReviewWriteReceipt,
        val rejectedEvent: ReviewSaveEvent?
    )

    private data class DeleteEnqueue(
        val submission: ReviewSubmission,
        val receipt: ReviewDeleteReceipt,
        val restore: ListenerReview?,
        val rejectedEvent: ReviewDeleteEvent?
    )

    private suspend fun sendSave(
        review: ListenerReview,
        epochAtStart: Long,
        awaitRemote: Boolean
    ): ReviewSaveResult {
        val seam = store ?: return ReviewSaveResult.FAILED
        val workId = review.workId
        val documentId = ListenerReviewCodec.documentId(workId, review.uid)
        val local = localEnqueueGate(documentId).withLock {
            val submission = synchronized(transitionLock) {
                if (!inScope(workId, epochAtStart) || _state.value.uid != review.uid) return@withLock null
                loads.begin(workId) // invalidate a frame captured before this admitted local attempt
                submissions.begin(workId, documentId)
            }
            val receipt = try {
                seam.enqueueReview(review)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReviewWriteReceipt.Rejected
            }
            if (receipt !is ReviewWriteReceipt.Queued) {
                val event = synchronized(transitionLock) {
                    if (!inScope(workId, epochAtStart) || _state.value.uid != review.uid || !submissions.isLatest(submission)) null
                    else {
                        // No accepted overlay or ownership changes on local rejection.
                        val current = _state.value
                        _state.value = current.copy(failedSave = current.failedSave + (documentId to review),
                            failedDelete = current.failedDelete - documentId)
                        submission.event(ReviewSaveResult.FAILED)
                    }
                }
                return@withLock SaveEnqueue(submission, receipt, event)
            }
            synchronized(transitionLock) {
                if (!inScope(workId, epochAtStart) || _state.value.uid != review.uid || !submissions.isLatest(submission)) {
                    return@withLock null
                }
                loads.begin(workId) // discard frames captured while local acceptance was suspended
                acceptedSubmissions[documentId] = submission
                recoveredSaves.remove(documentId)
                cancelRecovery()
                val current = _state.value
                _state.value = current.copy(pending = current.pending + (documentId to review),
                    deleting = current.deleting - documentId, failedSave = current.failedSave - documentId,
                    failedDelete = current.failedDelete - documentId)
            }
            SaveEnqueue(submission, receipt, null)
        } ?: return ReviewSaveResult.FAILED
        val submission = local.submission
        val receipt = local.receipt
        if (receipt !is ReviewWriteReceipt.Queued) {
            local.rejectedEvent?.let { _results.emit(it) }
            return ReviewSaveResult.FAILED
        }
        // Register the provided scope's one receipt owner before a caller-owned hook can suspend or cancel.
        val completion = startSaveCompletion(submission, review, epochAtStart, receipt)
        val hookIsCurrent = synchronized(transitionLock) {
            inScope(workId, epochAtStart) && _state.value.uid == review.uid
        }
        if (hookIsCurrent) onAccepted(review)
        if (completion != null) {
            return if (awaitRemote) completion.await() else ReviewSaveResult.QUEUED
        }
        // Without a provided scope, synchronous callers own delivery and waiting; no unscoped job is invented.
        _results.emit(submission.event(ReviewSaveResult.QUEUED))
        if (!awaitRemote) return ReviewSaveResult.QUEUED
        return finishSave(submission, workId, documentId, review, epochAtStart, receipt.awaitRemote())
    }

    /** One receipt owner commits independently of its structured, ordered notification delivery. */
    private fun startSaveCompletion(
        submission: ReviewSubmission, review: ListenerReview, epochAtStart: Long, receipt: ReviewWriteReceipt.Queued
    ): Deferred<ReviewSaveResult>? {
        val owner = scope ?: return null
        val completion = owner.async(start = CoroutineStart.LAZY) {
            coroutineContext.ensureActive()
            val current = synchronized(transitionLock) {
                inScope(review.workId, epochAtStart) && _state.value.uid == review.uid
            }
            if (!current) return@async ReviewSaveResult.FAILED
            // Register QUEUED first, but a held observer cannot delay consuming the backend verdict.
            val queuedNotice = launch(start = CoroutineStart.UNDISPATCHED) {
                _results.emit(submission.event(ReviewSaveResult.QUEUED))
            }
            val remote = receipt.awaitRemote()
            coroutineContext.ensureActive()
            val committed = synchronized(transitionLock) {
                finishSaveLocked(submission, review.workId, submission.documentId, review, epochAtStart, remote)
            }
            // Retain both notices in this owner; terminal delivery cannot pass QUEUED delivery.
            queuedNotice.join()
            coroutineContext.ensureActive()
            committed.event?.let { _results.emit(it) }
            committed.result
        }
        synchronized(transitionLock) {
            if (inScope(review.workId, epochAtStart) && _state.value.uid == review.uid) mutationCompletions += completion
            else completion.cancel()
        }
        completion.invokeOnCompletion {
            synchronized(transitionLock) { mutationCompletions -= completion }
        }
        completion.start() // Neither suspendable event delivery nor receipt I/O runs under transitionLock.
        return completion
    }

    /**
     * Applies one backend save verdict. A verdict that lost the ordering race
     * (a newer mutation, or a switched Work/uid) changes NOTHING.
     */
    private suspend fun finishSave(
        submission: ReviewSubmission, workId: String, documentId: String, review: ListenerReview,
        epochAtStart: Long, remote: ReviewRemoteResult
    ): ReviewSaveResult {
        val committed = synchronized(transitionLock) {
            finishSaveLocked(submission, workId, documentId, review, epochAtStart, remote)
        }
        committed.event?.let { _results.emit(it) }
        return committed.result
    }

    private fun finishSaveLocked(
        submission: ReviewSubmission, workId: String, documentId: String, review: ListenerReview,
        epochAtStart: Long, remote: ReviewRemoteResult
    ): SaveCommit {
        if (!inScope(workId, epochAtStart) || _state.value.uid != review.uid || acceptedSubmissions[documentId] != submission) {
            return SaveCommit(ReviewSaveResult.FAILED, null)
        }
        loads.begin(workId) // discard read frames captured before this accepted terminal verdict
        val current = _state.value
        val result = when (remote) {
            ReviewRemoteResult.PUBLISHED -> {
                _state.value = current.copy(pending = current.pending - documentId,
                    confirmed = (current.confirmed.filterNot { ListenerReviewCodec.documentId(it.workId, it.uid) == documentId } + review)
                        .sortedByDescending { it.createdAt })
                ReviewSaveResult.PUBLISHED
            }
            ReviewRemoteResult.FAILED -> {
                // A newer local rejection owns retry; this accepted terminal still retires its pending card.
                _state.value = current.copy(pending = current.pending - documentId,
                    failedSave = current.failedSave + (documentId to (current.failedSave[documentId] ?: review)))
                ReviewSaveResult.FAILED
            }
        }
        return SaveCommit(result, submission.event(result))
    }

    private suspend fun sendDelete(
        workId: String,
        documentId: String,
        epochAtStart: Long
    ): ReviewDeleteResult {
        val seam = store ?: return ReviewDeleteResult.FAILED
        val local = localEnqueueGate(documentId).withLock {
            val submission = synchronized(transitionLock) {
                if (!inScope(workId, epochAtStart) || ListenerReviewCodec.documentId(workId, _state.value.uid) != documentId) {
                    return@withLock null
                }
                loads.begin(workId)
                submissions.begin(workId, documentId)
            }
            val receipt = try {
                seam.enqueueDelete(documentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReviewDeleteReceipt.Rejected
            }
            if (receipt !is ReviewDeleteReceipt.Queued) {
                val event = synchronized(transitionLock) {
                    if (!inScope(workId, epochAtStart) || ListenerReviewCodec.documentId(workId, _state.value.uid) != documentId ||
                        !submissions.isLatest(submission)) null
                    else {
                        // The document was never accepted for removal; keep confirmed and prior overlays.
                        val current = _state.value
                        _state.value = current.copy(failedDelete = current.failedDelete + documentId,
                            failedSave = current.failedSave - documentId)
                        submission.deleteEvent(ReviewDeleteResult.FAILED)
                    }
                }
                return@withLock DeleteEnqueue(submission, receipt, null, event)
            }
            val restore = synchronized(transitionLock) {
                if (!inScope(workId, epochAtStart) || ListenerReviewCodec.documentId(workId, _state.value.uid) != documentId ||
                    !submissions.isLatest(submission)) return@withLock null
                loads.begin(workId)
                acceptedSubmissions[documentId] = submission
                recoveredSaves.remove(documentId)
                cancelRecovery()
                val current = _state.value
                val prior = current.confirmed.firstOrNull { ListenerReviewCodec.documentId(it.workId, it.uid) == documentId }
                _state.value = current.copy(
                    confirmed = current.confirmed.filterNot { ListenerReviewCodec.documentId(it.workId, it.uid) == documentId },
                    pending = current.pending - documentId, deleting = current.deleting + documentId,
                    failedSave = current.failedSave - documentId, failedDelete = current.failedDelete - documentId)
                prior
            }
            DeleteEnqueue(submission, receipt, restore, null)
        } ?: return ReviewDeleteResult.FAILED
        val submission = local.submission
        val receipt = local.receipt
        if (receipt !is ReviewDeleteReceipt.Queued) {
            local.rejectedEvent?.let { _deleteResults.emit(it) }
            return ReviewDeleteResult.FAILED
        }
        val restore = local.restore
        val completion = startDeleteCompletion(submission, workId, documentId, epochAtStart, receipt, restore)
        if (completion != null) return completion.await()
        // Scope-null compatibility: the synchronous caller owns notification delivery and receipt waiting.
        _deleteResults.emit(submission.deleteEvent(ReviewDeleteResult.QUEUED))
        val succeeded = receipt.awaitRemote()
        val committed = synchronized(transitionLock) {
            finishDeleteLocked(submission, workId, documentId, epochAtStart, succeeded, restore)
        }
        committed.event?.let { _deleteResults.emit(it) }
        return committed.result
    }

    /** A single registered DELETE owner outlives its caller and retains QUEUED before the terminal notice. */
    private fun startDeleteCompletion(
        submission: ReviewSubmission, workId: String, documentId: String, epochAtStart: Long,
        receipt: ReviewDeleteReceipt.Queued, restore: ListenerReview?
    ): Deferred<ReviewDeleteResult>? {
        val owner = scope ?: return null
        val completion = owner.async(start = CoroutineStart.LAZY) {
            coroutineContext.ensureActive()
            val current = synchronized(transitionLock) {
                inScope(workId, epochAtStart) && ListenerReviewCodec.documentId(workId, _state.value.uid) == documentId
            }
            if (!current) return@async ReviewDeleteResult.FAILED
            val queuedNotice = launch(start = CoroutineStart.UNDISPATCHED) {
                _deleteResults.emit(submission.deleteEvent(ReviewDeleteResult.QUEUED))
            }
            val succeeded = receipt.awaitRemote()
            coroutineContext.ensureActive()
            val committed = synchronized(transitionLock) {
                finishDeleteLocked(submission, workId, documentId, epochAtStart, succeeded, restore)
            }
            queuedNotice.join()
            coroutineContext.ensureActive()
            committed.event?.let { _deleteResults.emit(it) }
            committed.result
        }
        synchronized(transitionLock) {
            if (inScope(workId, epochAtStart) && ListenerReviewCodec.documentId(workId, _state.value.uid) == documentId) {
                mutationCompletions += completion
            } else completion.cancel()
        }
        completion.invokeOnCompletion {
            synchronized(transitionLock) { mutationCompletions -= completion }
        }
        completion.start() // Notification delivery and receipt I/O never run under transitionLock.
        return completion
    }

    /** Existing DELETE verdict rules, committed once under transitionLock for either ownership mode. */
    private fun finishDeleteLocked(
        submission: ReviewSubmission, workId: String, documentId: String, epochAtStart: Long,
        succeeded: Boolean, restore: ListenerReview?
    ): DeleteCommit {
        val result = if (succeeded) ReviewDeleteResult.DELETED else ReviewDeleteResult.FAILED
        if (!inScope(workId, epochAtStart) || ListenerReviewCodec.documentId(workId, _state.value.uid) != documentId ||
            acceptedSubmissions[documentId] != submission) return DeleteCommit(result, null)
        loads.begin(workId)
        val current = _state.value
        _state.value = current.copy(deleting = current.deleting - documentId,
            failedDelete = if (succeeded) current.failedDelete else current.failedDelete + documentId,
            confirmed = if (succeeded) current.confirmed else restoreInto(current.confirmed, restore))
        return DeleteCommit(result, submission.deleteEvent(result))
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
        val rejected = synchronized(transitionLock) { submissions.begin(workId, documentId) }
        _results.emit(rejected.event(ReviewSaveResult.FAILED))
        return ReviewSaveResult.FAILED
    }
}
