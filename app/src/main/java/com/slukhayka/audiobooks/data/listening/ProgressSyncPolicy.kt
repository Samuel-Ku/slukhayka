package com.slukhayka.audiobooks.data.listening

/**
 * ADR-0023 (spec-43 T6) — the server-side mirror of one Edition's Listening
 * State. Only the fields Progress Sync carries: position, chapter,
 * completion, preferred speed — never Library Entries, Metadata Overrides or
 * Tombstones, and never another listener's rows.
 */
data class RemoteListeningState(
    val editionId: String,
    val chapterIndex: Int,
    val positionSeconds: Long,
    val isCompleted: Boolean,
    val preferredSpeed: Float?,
    /** Firestore's SERVER timestamp of the newest write — the ONLY clock that arbitrates. */
    val updatedAtServerMs: Long
)

/**
 * ADR-0023 (spec-43 T6) — the pure rules of Progress Sync.
 *
 * Last-write-wins by the server timestamp: a remote mirror applies only when
 * it is STRICTLY newer than the newest server state this device has already
 * seen; pushes always claim the latest state and are throttled so the 5-
 * second position ticks stay off the wire (only pauses/seeks/completions go
 * immediately). Device clocks never arbitrate — they only pace the throttle.
 */
object ProgressSyncPolicy {

    /** The quiet ceiling between automatic pushes while playback runs. */
    const val MIN_PUSH_INTERVAL_MS = 60_000L

    /**
     * True when the cloud holds a state this device has not seen yet — the
     * «почав на телефоні — продовж тут» moment. Equal stamps mean the local
     * view already reflects the server (its own push), so nothing moves.
     */
    fun shouldPull(remote: RemoteListeningState?, syncedAtServerMs: Long?): Boolean {
        if (remote === null) return false
        if (remote.updatedAtServerMs <= 0L) return false
        return syncedAtServerMs == null || remote.updatedAtServerMs > syncedAtServerMs
    }

    /**
     * True when a save point may reach the wire. [immediate] marks the honest
     * moments (pause, completion) that bypass the pacing; periodic ticks wait
     * out [MIN_PUSH_INTERVAL_MS] since the last attempt on that Edition.
     */
    fun shouldPush(nowMs: Long, lastAttemptMs: Long?, immediate: Boolean): Boolean {
        if (immediate) return true
        if (lastAttemptMs == null) return true
        return nowMs - lastAttemptMs >= MIN_PUSH_INTERVAL_MS
    }

    /** Deterministic document key — same shape as `book_reviews` (`${workId}_${uid}`). */
    fun documentId(uid: String, editionId: String): String = "${uid}_${editionId}"
}

/**
 * ADR-0023 (spec-43 T6) — the document codec for the `listening_state` base:
 * bounds mirror the security rules so anything the rules accept decodes, and
 * anything corrupt decodes to null (a miss, never a crash).
 *
 * The `updatedAt` field is written BY THE SERVER (`FieldValue.serverTimestamp`
 * at the transport); the codec reads it back but never fabricates it.
 */
object ProgressSyncCodec {

    const val FIELD_EDITION_ID = "editionId"
    const val FIELD_UID = "uid"
    const val FIELD_CHAPTER_INDEX = "chapterIndex"
    const val FIELD_POSITION_SECONDS = "positionSeconds"
    const val FIELD_IS_COMPLETED = "isCompleted"
    const val FIELD_PREFERRED_SPEED = "preferredSpeed"
    const val FIELD_UPDATED_AT = "updatedAt"

    private const val ID_MAX = 300
    private const val POSITION_MAX_SECONDS = 86_400L * 100 // a hundred days of one chapter
    private const val SPEED_MIN = 0.25f
    private const val SPEED_MAX = 4.0f

    /** The payload WITHOUT `updatedAt` — the transport adds the server stamp. */
    fun toDocument(uid: String, state: RemoteListeningState): Map<String, Any> {
        val doc = mutableMapOf<String, Any>(
            FIELD_EDITION_ID to state.editionId,
            FIELD_UID to uid,
            FIELD_CHAPTER_INDEX to state.chapterIndex,
            FIELD_POSITION_SECONDS to state.positionSeconds,
            FIELD_IS_COMPLETED to state.isCompleted
        )
        state.preferredSpeed?.let { doc[FIELD_PREFERRED_SPEED] = it }
        return doc
    }

    fun fromDocument(document: Map<String, Any>?): RemoteListeningState? {
        if (document == null) return null
        val editionId = document[FIELD_EDITION_ID] as? String ?: return null
        if (editionId.isEmpty() || editionId.length > ID_MAX) return null
        val chapterIndex = (document[FIELD_CHAPTER_INDEX] as? Number)?.toInt() ?: return null
        val positionSeconds = (document[FIELD_POSITION_SECONDS] as? Number)?.toLong() ?: return null
        val isCompleted = document[FIELD_IS_COMPLETED] as? Boolean ?: return null
        if (chapterIndex < 0) return null
        if (positionSeconds < 0L || positionSeconds > POSITION_MAX_SECONDS) return null
        val speed = when (val raw = document[FIELD_PREFERRED_SPEED]) {
            null -> null
            is Number -> raw.toFloat().takeIf { it in SPEED_MIN..SPEED_MAX } ?: return null
            else -> return null
        }
        val updatedAt = (document[FIELD_UPDATED_AT] as? Number)?.toLong() ?: return null
        if (updatedAt <= 0L) return null // no server vouch, no ordering
        return RemoteListeningState(
            editionId = editionId,
            chapterIndex = chapterIndex,
            positionSeconds = positionSeconds,
            isCompleted = isCompleted,
            preferredSpeed = speed,
            updatedAtServerMs = updatedAt
        )
    }
}
