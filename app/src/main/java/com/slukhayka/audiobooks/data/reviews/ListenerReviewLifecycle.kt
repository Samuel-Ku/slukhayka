package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Spec-620 (#623) — the Work-scoped state one screen reads: the last CONFIRMED
 * server snapshot, the local pending overlay, and whether the last read failed.
 *
 * [visible] is what a surface renders: server truth with the pending cards on
 * top, newest first. A pending card never enters the confirmed snapshot until
 * the backend acknowledges it, so a headline average can count only confirmed
 * votes.
 */
data class ListenerReviewState(
    /** The Work this state belongs to; empty before the module is opened. */
    val workId: String = "",
    val confirmed: List<ListenerReview> = emptyList(),
    /** Local acceptances awaiting a backend verdict, keyed by document id. */
    val pending: Map<String, ListenerReview> = emptyMap(),
    /** True when the last read failed: [confirmed] is the last good snapshot. */
    val readFailed: Boolean = false
) {
    /** Confirmed server truth overlaid with the pending cards, newest first. */
    val visible: List<ListenerReview>
        get() = (
            confirmed.filterNot { ListenerReviewCodec.documentId(it.workId, it.uid) in pending } +
                pending.values
            ).sortedByDescending { it.createdAt }
}

/**
 * Spec-620 (#623) — ONE Work-scoped lifecycle module for listener reviews:
 * reading, local acceptance, the pending overlay and the backend
 * acknowledgement all live here instead of being spread across
 * `MainViewModel`, the book page and the Firestore store.
 *
 * The rules it owns:
 * - state is scoped to one Work — opening another Work drops the previous
 *   reviews, pending cards and in-flight reads instead of leaking them;
 * - a failed read keeps the last confirmed snapshot (and says so); a genuine
 *   successful empty clears it;
 * - only a real local acceptance creates a pending card — a local reject
 *   never does;
 * - a newer edit supersedes a late acknowledgement of an older save for the
 *   same document;
 * - a confirmed acknowledgement moves the review into the confirmed snapshot
 *   without waiting for the next read (no flicker);
 * - a missing store (no Firebase) means sending is unavailable: no pending
 *   card is fabricated, and the caller gets an honest FAILED.
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

    private val submissions = ReviewSubmissionGate()
    private val loads = ReviewLoadGate()

    /**
     * Scopes the module to [workId]. A different Work starts from an empty
     * state — nothing private from the previous Work survives the switch.
     */
    fun open(workId: String) {
        if (_state.value.workId != workId) {
            _state.value = ListenerReviewState(workId = workId)
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
     * Forgets a locally accepted card that is no longer real — the delete path
     * of #626 gives this its full ordered lifecycle; until then the book-page
     * editor needs the honest immediate removal.
     */
    fun dropPending(workId: String, uid: String) {
        val documentId = ListenerReviewCodec.documentId(workId, uid)
        _state.update { it.copy(pending = it.pending - documentId) }
    }

    /**
     * Creates or edits the listener's review of the open Work.
     *
     * Local acceptance appears as a pending card immediately; the backend
     * acknowledgement later turns it into published or failed. The returned
     * value is the same outcome the screen receives through [results].
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
        val seam = store
        if (workId != _state.value.workId || seam == null || uid.isBlank() ||
            !ListenerReviewLimits.isValidRating(rating)
        ) {
            // Honest local rejection: announce it, but never fabricate a card.
            val rejected = submissions.begin(workId, documentId)
            val event = rejected.event(ReviewSaveResult.FAILED)
            _results.emit(event)
            return ReviewSaveResult.FAILED
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
        val submission = submissions.begin(workId, documentId)
        // Local acceptance FIRST — the listener sees their card instantly.
        _state.update { it.copy(pending = it.pending + (documentId to review)) }

        val receipt = try {
            seam.enqueueReview(review)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ReviewWriteReceipt.Rejected
        }

        var outcome = ReviewSaveResult.FAILED
        followReviewWrite(
            receipt = receipt,
            onVisibleResult = { result ->
                if (submissions.isLatest(submission)) {
                    if (result == ReviewSaveResult.FAILED) {
                        // The local acceptance did not hold: retract the card.
                        _state.update { it.copy(pending = it.pending - documentId) }
                    }
                    outcome = result
                    _results.emit(submission.event(result))
                }
            },
            onRemoteResult = {
                if (submissions.isLatest(submission)) {
                    // Either backend verdict ends the local pending badge.
                    _state.update { it.copy(pending = it.pending - documentId) }
                }
            }
        )

        if (outcome == ReviewSaveResult.PUBLISHED && submissions.isLatest(submission)) {
            // Move the accepted review into the confirmed snapshot right away
            // — the next read must not be needed to make the card real.
            _state.update { state ->
                state.copy(
                    confirmed = (
                        state.confirmed.filterNot {
                            ListenerReviewCodec.documentId(it.workId, it.uid) == documentId
                        } + review
                        ).sortedByDescending { it.createdAt }
                )
            }
        }
        return outcome
    }
}
