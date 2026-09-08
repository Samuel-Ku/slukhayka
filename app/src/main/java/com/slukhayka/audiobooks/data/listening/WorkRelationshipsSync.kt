package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.data.identity.ListenerIdentity
import com.slukhayka.audiobooks.data.identity.LocalOnlyIdentity

/**
 * #581 W0.3 (R-W3, R-W13, ADR-0034) — the Android writer of the
 * `work_relationships` collection: Library Entries and tombstones mirror to
 * the same documents the web reads, so the browser's Медіатека and the
 * catalog's hide filter agree with the phone. The merge RULE lives in
 * [WorkRelationshipsPolicy]; this class only sequences the honest moments:
 *
 *  - `pushEntry` — a Work enters the library (import doors, re-add over a
 *    tombstone) and the relationship becomes `entry`;
 *  - `pushTombstone` — the listener removes the Work (both deletion levels)
 *    and the relationship becomes `tombstone`.
 *
 * Every path degrades silently — no Firebase config, a switched-off toggle
 * or a `local-…` identity leaves the app exactly as it was, never an
 * exception into the library flow (same contract as
 * [ProgressSyncController]). Sync is on by default (ADR-0023 п.1): the rows
 * carry nothing until the listener's own devices share one profile.
 */
class WorkRelationshipsSync(
    private val identity: ListenerIdentity,
    /**
     * Null without Firebase keys — then Work-relationship sync does not
     * exist and both entry points return immediately.
     */
    private val store: WorkRelationshipsStore?,
    private val isEnabled: () -> Boolean = { true },
) {

    suspend fun pushEntry(mergeKey: String, title: String, author: String) {
        push(mergeKey, WorkRelationshipsCodec.STATE_ENTRY, title, author)
    }

    suspend fun pushTombstone(mergeKey: String) {
        // A hide carries no display cache at the call site: the mergeKey is
        // the honest provenance stand-in, never a fabricated author.
        push(mergeKey, WorkRelationshipsCodec.STATE_TOMBSTONE, mergeKey, mergeKey)
    }

    private suspend fun push(mergeKey: String, state: String, title: String, author: String) {
        if (!isEnabled()) return
        val remoteStore = store ?: return
        if (mergeKey.isBlank()) return // a blank-key local Work has no cloud anchor
        val uid = identityUid() ?: return

        val row = WorkRelationshipRow(
            mergeKey = mergeKey,
            state = state,
            title = title.ifBlank { mergeKey },
            author = author.ifBlank { mergeKey },
            uid = uid,
            updatedAtServerMs = 0L // stamped by the server at the transport
        )
        if (!isEnabled()) return // the switch stops it mid-flight too
        remoteStore.push(uid, row)
    }

    /**
     * The listener uid to sync under, or null when there is nothing to sync:
     * a `local-…` profile has no cloud account behind it, so uploading would
     * fork the listener's identity.
     */
    private suspend fun identityUid(): String? {
        val profile = runCatching { identity.ensure() }.getOrNull() ?: return null
        return profile.uid.takeUnless { it.startsWith(LocalOnlyIdentity.LOCAL_UID_PREFIX) }
    }
}
