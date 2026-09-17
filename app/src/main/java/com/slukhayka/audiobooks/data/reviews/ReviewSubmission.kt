package com.slukhayka.audiobooks.data.reviews

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Spec-620 (#623) — the submission vocabulary moved out of `MainViewModel`.
 *
 * It lives beside the lifecycle module that owns the ordering now: the module
 * decides which acknowledgement is still the latest, and the screen only reads
 * the resulting one-shot event.
 */

/** One-shot visible outcome of submitting a listener review. */
enum class ReviewSaveResult {
    PUBLISHED,
    QUEUED,
    FAILED
}

/** One submission outcome, scoped to both its Work and deterministic review document. */
data class ReviewSaveEvent(
    val workId: String,
    val documentId: String,
    val generation: Long,
    val result: ReviewSaveResult
)

internal data class ReviewSubmission(
    val workId: String,
    val documentId: String,
    val generation: Long
) {
    fun event(result: ReviewSaveResult): ReviewSaveEvent = ReviewSaveEvent(
        workId = workId,
        documentId = documentId,
        generation = generation,
        result = result
    )

    /** Spec-620 (#626) — the same ordering identity, for a delete outcome. */
    fun deleteEvent(result: ReviewDeleteResult): ReviewDeleteEvent = ReviewDeleteEvent(
        workId = workId,
        documentId = documentId,
        generation = generation,
        result = result
    )
}

/** Rejects acknowledgements superseded by a newer write to the same review document. */
internal class ReviewSubmissionGate {
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    fun begin(workId: String, documentId: String): ReviewSubmission = ReviewSubmission(
        workId = workId,
        documentId = documentId,
        generation = generations.computeIfAbsent(documentId) { AtomicLong() }.incrementAndGet()
    )

    fun isLatest(submission: ReviewSubmission): Boolean =
        generations[submission.documentId]?.get() == submission.generation
}

internal data class ReviewLoadRequest(
    val workId: String,
    val generation: Long
)

/** Prevents an older fetch of one Work from replacing a newer server snapshot. */
internal class ReviewLoadGate {
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    fun begin(workId: String): ReviewLoadRequest = ReviewLoadRequest(
        workId = workId,
        generation = generations.computeIfAbsent(workId) { AtomicLong() }.incrementAndGet()
    )

    fun isLatest(request: ReviewLoadRequest): Boolean =
        generations[request.workId]?.get() == request.generation
}

/**
 * Delivers the local queue result before waiting for Firestore's backend Task.
 * Remote failure remains a visible event; caller cancellation still escapes.
 */
internal suspend fun followReviewWrite(
    receipt: ReviewWriteReceipt,
    onVisibleResult: suspend (ReviewSaveResult) -> Unit,
    onRemoteResult: suspend (ReviewRemoteResult) -> Unit
) {
    when (receipt) {
        ReviewWriteReceipt.Rejected -> onVisibleResult(ReviewSaveResult.FAILED)
        is ReviewWriteReceipt.Queued -> {
            onVisibleResult(ReviewSaveResult.QUEUED)
            val remoteResult = receipt.awaitRemote()
            onRemoteResult(remoteResult)
            onVisibleResult(
                if (remoteResult == ReviewRemoteResult.PUBLISHED) {
                    ReviewSaveResult.PUBLISHED
                } else {
                    ReviewSaveResult.FAILED
                }
            )
        }
    }
}
