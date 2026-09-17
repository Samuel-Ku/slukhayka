package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.CancellationException

/**
 * Spec-620 (#626) — separates the immediate LOCAL acceptance of a delete from
 * its backend verdict, exactly as [ReviewWriteReceipt] does for a save. A
 * missing/failing transport is [Rejected]; a queued delete owns the separate,
 * cancellable acknowledgement.
 */
sealed interface ReviewDeleteReceipt {
    data object Rejected : ReviewDeleteReceipt

    class Queued internal constructor(
        private val acknowledgement: suspend () -> Boolean
    ) : ReviewDeleteReceipt {
        suspend fun awaitRemote(): Boolean = try {
            acknowledgement()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Spec-620 (#623) — the honest outcome of READING one Work's reviews. The
 * existing [ListenerReviewsStore.getReviews] keeps its degrade-to-empty
 * convenience for non-UI callers; the lifecycle module needs the three states
 * apart, because a failed read must keep the last confirmed snapshot while a
 * genuine empty must clear it.
 */
sealed interface ReviewReadResult {
    data class Data(val reviews: List<ListenerReview>) : ReviewReadResult
    data object Empty : ReviewReadResult
    data object Failure : ReviewReadResult
}

/** The backend's eventual verdict after a review was accepted by the local queue. */
enum class ReviewRemoteResult {
    PUBLISHED,
    FAILED
}

/** Separates immediate local enqueue from the backend acknowledgement. */
sealed interface ReviewWriteReceipt {
    data object Rejected : ReviewWriteReceipt

    class Queued internal constructor(
        private val acknowledgement: suspend () -> ReviewRemoteResult
    ) : ReviewWriteReceipt {
        suspend fun awaitRemote(): ReviewRemoteResult = try {
            acknowledgement()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ReviewRemoteResult.FAILED
        }
    }
}

/**
 * Spec-40 #277 — the listener-reviews store behind a pure JVM seam, shaped
 * exactly like [com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore]'s
 * philosophy (and the [com.slukhayka.audiobooks.data.search.SearchCache]
 * construction): best-effort and silent by contract — a miss, a failure or
 * an unreadable document yields an empty list / an empty map / false, never
 * an exception. The read/write POLICY lives in the seam as default methods
 * over a minimal document transport, so it is fixture-testable on an
 * in-memory fake; only the transport is Android glue.
 *
 * Documents live under the deterministic key `${workId}_${uid}` — one review
 * per listener per Work — and every list comes back NEWEST FIRST by
 * `createdAt` regardless of what the transport ordered.
 */
interface ListenerReviewsStore {

    /**
     * Every review of one Work, newest first. A miss, a failure or corrupt
     * documents contribute nothing — empty on a silent work, never an error.
     */
    suspend fun getReviews(workId: String): List<ListenerReview> {
        val documents = runCatching { queryWorkDocuments(workId) }.getOrNull() ?: return emptyList()
        return decode(documents)
    }

    /**
     * BATCH read for several Works at once (the transport chunks internally
     * where its query bounds demand). Only Works that actually have reviews
     * appear in the map — batch gaps contribute nothing; a failing chunk is
     * silently absent.
     */
    suspend fun getForWorks(workIds: List<String>): Map<String, List<ListenerReview>> {
        val ids = workIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val documents = runCatching { queryWorksDocuments(ids) }.getOrNull() ?: return emptyMap()
        return decode(documents)
            .groupBy { it.workId }
            .filterKeys { it in ids.toSet() }
    }

    /**
     * Spec-620 (#623) — reads one Work's reviews with the three outcomes kept
     * apart. A transport failure is [ReviewReadResult.Failure] (NOT an empty
     * community), a successful empty answer is [ReviewReadResult.Empty], and
     * real documents are [ReviewReadResult.Data] newest-first.
     */
    suspend fun readReviews(workId: String): ReviewReadResult {
        val documents = queryWorkDocumentsOrNull(workId) ?: return ReviewReadResult.Failure
        val reviews = decode(documents)
        return if (reviews.isEmpty()) ReviewReadResult.Empty else ReviewReadResult.Data(reviews)
    }

    /**
     * Enqueues one idempotent review write without waiting for the backend.
     * Invalid input or a synchronous local rejection returns [ReviewWriteReceipt.Rejected]
     * before the UI can claim it was queued. A queued receipt owns the separate,
     * cancellable remote acknowledgement.
     */
    suspend fun enqueueReview(review: ListenerReview): ReviewWriteReceipt {
        if (!ListenerReviewLimits.isWritable(review)) return ReviewWriteReceipt.Rejected
        return try {
            enqueueDocument(
                ListenerReviewCodec.documentId(review.workId, review.uid),
                ListenerReviewCodec.toMap(review)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ReviewWriteReceipt.Rejected
        }
    }

    /** Compatibility result for non-UI callers that need the remote verdict. */
    suspend fun putReview(review: ListenerReview): Boolean = when (val receipt = enqueueReview(review)) {
        ReviewWriteReceipt.Rejected -> false
        is ReviewWriteReceipt.Queued -> receipt.awaitRemote() == ReviewRemoteResult.PUBLISHED
    }

    /** Best-effort removal of one listener's review; false on any failure. */
    suspend fun deleteReview(workId: String, uid: String): Boolean = try {
        removeDocument(ListenerReviewCodec.documentId(workId, uid))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * Spec-620 (#626) — locally accepts one delete and returns its separate
     * backend verdict. The default keeps the old blocking contract for
     * implementations that cannot split the two; the Firestore store overrides
     * it so the call returns as soon as the delete is in the local queue.
     */
    suspend fun enqueueDelete(documentId: String): ReviewDeleteReceipt = try {
        val removed = removeDocument(documentId)
        ReviewDeleteReceipt.Queued { removed }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ReviewDeleteReceipt.Rejected
    }

    // ---------------------------------------------------------------------
    // Transport — the ONLY part an implementation supplies. May throw; the
    // seam's policy methods fail closed around every call.
    // ---------------------------------------------------------------------

    /** Raw documents of one Work's reviews (any order; the seam sorts). */
    suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>>

    /**
     * Spec-620 (#623) — the same read, but a transport failure is null rather
     * than an empty list. Implementations that can tell the two apart override
     * this; the default keeps the old degrade-to-empty contract.
     */
    suspend fun queryWorkDocumentsOrNull(workId: String): List<Map<String, Any>>? = try {
        queryWorkDocuments(workId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Raw documents across several Works — chunked internally by the impl. */
    suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>>

    /** One idempotent local enqueue plus its independent remote acknowledgement. */
    suspend fun enqueueDocument(
        documentId: String,
        document: Map<String, Any>
    ): ReviewWriteReceipt

    /** One document delete; true when accepted. */
    suspend fun removeDocument(documentId: String): Boolean

    /**
     * The newest-first invariant lives HERE, not in the transport: decoded
     * corrupt docs are dropped (a miss), survivors sorted by createdAt desc
     * so the UI order never depends on the query's own ordering.
     */
    private fun decode(documents: List<Map<String, Any>>): List<ListenerReview> =
        documents.mapNotNull { ListenerReviewCodec.fromMap(it) }
            .sortedByDescending { it.createdAt }
}
