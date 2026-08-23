package com.slukhayka.audiobooks.data.reviews

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
     * Idempotent write of one review (`set()` — a re-edit replaces the same
     * document, never duplicates). An invalid review (bad identity fields,
     * out-of-range rating) is refused with false BEFORE any I/O; a failing
     * transport also reports false. True means the document was durably
     * accepted by the local layer (offline, that is the persistence queue —
     * spec-40 #280).
     */
    suspend fun putReview(review: ListenerReview): Boolean {
        if (!ListenerReviewLimits.isWritable(review)) return false
        return runCatching {
            setDocument(
                ListenerReviewCodec.documentId(review.workId, review.uid),
                ListenerReviewCodec.toMap(review)
            )
        }.getOrDefault(false)
    }

    /** Best-effort removal of one listener's review; false on any failure. */
    suspend fun deleteReview(workId: String, uid: String): Boolean =
        runCatching { removeDocument(ListenerReviewCodec.documentId(workId, uid)) }.getOrDefault(false)

    // ---------------------------------------------------------------------
    // Transport — the ONLY part an implementation supplies. May throw; the
    // seam's policy methods fail closed around every call.
    // ---------------------------------------------------------------------

    /** Raw documents of one Work's reviews (any order; the seam sorts). */
    suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>>

    /** Raw documents across several Works — chunked internally by the impl. */
    suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>>

    /** One idempotent document write; true when durably accepted locally. */
    suspend fun setDocument(documentId: String, document: Map<String, Any>): Boolean

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
