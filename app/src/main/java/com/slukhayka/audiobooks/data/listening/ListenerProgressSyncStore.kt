package com.slukhayka.audiobooks.data.listening

/**
 * ADR-0023 (spec-43 T6) — the Progress Sync store behind a pure JVM seam,
 * shaped exactly like [com.slukhayka.audiobooks.data.reviews.ListenerReviewsStore]'s
 * philosophy: best-effort and silent by contract — a miss, a failure or a
 * corrupt document yields null / false, never an exception. The read/write
 * POLICY lives in the seam's default methods over a minimal document
 * transport, so it is fixture-testable on an in-memory fake; only the
 * transport is Android glue ([FirestoreListenerProgressSyncStore]).
 *
 * Documents live under the deterministic key `${uid}_${editionId}` — one
 * mirror row per listener per Edition — and the FIELD uid is the ownership
 * anchor the security rules check.
 */
interface ListenerProgressSyncStore {

    /**
     * The cloud's Listening State for one Edition of one listener, or null
     * when it does not exist yet / failed / did not decode.
     */
    suspend fun pull(uid: String, editionId: String): RemoteListeningState? {
        val document = runCatching { fetchDocument(ProgressSyncPolicy.documentId(uid, editionId)) }
            .getOrNull()
            ?: return null
        return ProgressSyncCodec.fromDocument(document)
    }

    /**
     * Best-effort upload of this device's state. The transport stamps the
     * write with `FieldValue.serverTimestamp()` — the codec never fabricates
     * an ordering clock. Returns the SERVER timestamp of the accepted write
     * when it can be read back (null offline — the persistence queue still
     * delivers it, the ledger just keeps its older mark).
     */
    suspend fun push(uid: String, state: RemoteListeningState): Long? {
        val id = ProgressSyncPolicy.documentId(uid, state.editionId)
        val accepted = runCatching { writeDocument(id, ProgressSyncCodec.toDocument(uid, state)) }
            .getOrDefault(false)
        if (!accepted) return null
        return runCatching { readServerUpdatedAtMs(id) }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // Transport — the ONLY part an implementation supplies. May throw; the
    // seam's policy methods fail closed around every call.
    // ---------------------------------------------------------------------

    /** The raw `listening_state` document, or null when absent. */
    suspend fun fetchDocument(documentId: String): Map<String, Any>?

    /** One idempotent server-stamped write (`set`); true when durably queued/committed. */
    suspend fun writeDocument(documentId: String, fields: Map<String, Any>): Boolean

    /** The SERVER timestamp of the newest accepted write, from the server source. */
    suspend fun readServerUpdatedAtMs(documentId: String): Long?
}
