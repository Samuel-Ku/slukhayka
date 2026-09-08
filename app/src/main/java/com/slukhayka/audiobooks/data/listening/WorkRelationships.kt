package com.slukhayka.audiobooks.data.listening

/**
 * #581 W0.3 (R-W3, R-W13, ADR-0034) — the Android mirror of the web's
 * `web/src/sync/workRelationships.ts`: one row per (listener, Work) in the
 * `work_relationships` collection, state `entry | tombstone`, LWW by SERVER
 * time with the tombstone winning every tie. The document id shape
 * (`{uid}_{mergeKey}`), the field names, the bounds and the tie-break are
 * pinned IDENTICALLY on both platforms by fixture tests over the same
 * vectors — a web row and a Kotlin row are the same document.
 *
 * The seam follows [ListenerProgressSyncStore]'s house pattern (ADR-0023):
 * policy in pure default methods over a minimal document transport; only
 * the transport is Android glue ([FirestoreWorkRelationshipsStore]).
 */
object WorkRelationshipsCodec {
    const val COLLECTION = "work_relationships"
    const val FIELD_MERGE_KEY = "mergeKey"
    const val FIELD_STATE = "state"
    const val FIELD_TITLE = "title"
    const val FIELD_AUTHOR = "author"
    const val FIELD_UID = "uid"
    const val FIELD_UPDATED_AT = "updatedAt"

    const val STATE_ENTRY = "entry"
    const val STATE_TOMBSTONE = "tombstone"

    const val ID_MAX = 300
    const val TITLE_MAX = 500
    const val AUTHOR_MAX = 300

    /** `{uid}_{mergeKey}` — the same key shape as `listening_state`. */
    fun documentId(uid: String, mergeKey: String): String = "${uid}_$mergeKey"

    /**
     * The document WITHOUT `updatedAt` — the transport stamps the write with
     * `FieldValue.serverTimestamp()`; the client never fabricates the clock.
     */
    fun toDocument(uid: String, row: WorkRelationshipRow): Map<String, Any> = mapOf(
        FIELD_MERGE_KEY to row.mergeKey,
        FIELD_STATE to row.state,
        FIELD_TITLE to row.title,
        FIELD_AUTHOR to row.author,
        FIELD_UID to uid,
    )

    /**
     * The closed-shape decoder: exactly the six contract fields, bounds
     * enforced, unknown fields rejected. A corrupt document is an honest
     * miss (null), never a crash — degrade-never by contract.
     */
    fun fromDocument(document: Map<String, Any>?): WorkRelationshipRow? {
        if (document == null) return null
        if (document.keys != setOf(FIELD_MERGE_KEY, FIELD_STATE, FIELD_TITLE, FIELD_AUTHOR, FIELD_UID, FIELD_UPDATED_AT)) {
            return null
        }
        val mergeKey = document[FIELD_MERGE_KEY] as? String ?: return null
        if (mergeKey.isEmpty() || mergeKey.length > ID_MAX) return null
        val state = document[FIELD_STATE] as? String ?: return null
        if (state != STATE_ENTRY && state != STATE_TOMBSTONE) return null
        val title = document[FIELD_TITLE] as? String ?: return null
        if (title.isEmpty() || title.length > TITLE_MAX) return null
        val author = document[FIELD_AUTHOR] as? String ?: return null
        if (author.isEmpty() || author.length > AUTHOR_MAX) return null
        val uid = document[FIELD_UID] as? String ?: return null
        if (uid.isEmpty()) return null
        val updatedAt = document[FIELD_UPDATED_AT] as? Long ?: return null
        if (updatedAt <= 0L) return null
        return WorkRelationshipRow(mergeKey, state, title, author, uid, updatedAt)
    }
}

/** One decoded `work_relationships` row (the sync-layer projection; `updatedAt` is server-vouched). */
data class WorkRelationshipRow(
    val mergeKey: String,
    val state: String,
    val title: String,
    val author: String,
    val uid: String,
    val updatedAtServerMs: Long,
)

/**
 * The ONE merge rule of the initiative (ADR-0034), shared verbatim with web:
 * newer server stamp wins; a stamp tie goes to the tombstone; equal states
 * on a tie keep the incoming display data (the freshest claim).
 */
object WorkRelationshipsPolicy {
    fun merge(local: WorkRelationshipRow, incoming: WorkRelationshipRow): WorkRelationshipRow {
        if (incoming.updatedAtServerMs > local.updatedAtServerMs) return incoming
        if (incoming.updatedAtServerMs < local.updatedAtServerMs) return local
        val localTombstone = local.state == WorkRelationshipsCodec.STATE_TOMBSTONE
        val incomingTombstone = incoming.state == WorkRelationshipsCodec.STATE_TOMBSTONE
        if (localTombstone != incomingTombstone) {
            return if (localTombstone) local else incoming
        }
        return incoming
    }
}

/**
 * The seam: the policy lives in these default methods over a minimal
 * transport, exactly like [ListenerProgressSyncStore]. Every failure
 * degrades to null / an empty list / false — never an exception.
 */
interface WorkRelationshipsStore {

    /** The cloud row for one Work of one listener, or null when absent/failed/corrupt. */
    suspend fun pull(uid: String, mergeKey: String): WorkRelationshipRow? {
        val document = runCatching { fetchDocument(WorkRelationshipsCodec.documentId(uid, mergeKey)) }
            .getOrNull()
            ?: return null
        return WorkRelationshipsCodec.fromDocument(document)
    }

    /**
     * Best-effort upload. The transport stamps the write with
     * `FieldValue.serverTimestamp()`; the returned server stamp (when it can
     * be read back) is the caller's sync point. Null when not accepted.
     */
    suspend fun push(uid: String, row: WorkRelationshipRow): Long? {
        val id = WorkRelationshipsCodec.documentId(uid, row.mergeKey)
        val accepted = runCatching { writeDocument(id, WorkRelationshipsCodec.toDocument(uid, row)) }
            .getOrDefault(false)
        if (!accepted) return null
        return runCatching { readServerUpdatedAtMs(id) }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // Transport — the ONLY part an implementation supplies. May throw; the
    // seam's policy methods fail closed around every call.
    // ---------------------------------------------------------------------

    /** The raw `work_relationships` document, or null when absent. */
    suspend fun fetchDocument(documentId: String): Map<String, Any>?

    /** One idempotent server-stamped write (`set`); true when durably queued/committed. */
    suspend fun writeDocument(documentId: String, fields: Map<String, Any>): Boolean

    /** The SERVER timestamp of the newest accepted write, from the server source. */
    suspend fun readServerUpdatedAtMs(documentId: String): Long?
}
