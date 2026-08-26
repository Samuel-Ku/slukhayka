package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.identity.ListenerIdentity
import com.slukhayka.audiobooks.data.identity.LocalOnlyIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ADR-0023 (spec-43 T6) — the narrow view of the Listening State Store that
 * Progress Sync needs. [ListeningStateStore] implements it directly; tests
 * supply an in-memory fake, so the controller stays JVM-pure.
 */
interface ProgressMirror {
    /** The Edition anchor of a book's Listening State (ADR-0007), or null when unknown yet. */
    suspend fun editionIdForSync(bookId: String): String?

    /** The current row of one Edition — exactly what a resume would read. */
    suspend fun progressByEdition(editionId: String): PlaybackProgressEntity?

    /**
     * Applies a remote mirror into the local row. Local recency and THIS
     * device's pause marker stay local: Smart Rewind belongs to the pause
     * made here (ADR-0003) and is never imported from the cloud.
     */
    suspend fun applyRemoteProgress(
        bookId: String,
        state: RemoteListeningState,
        nowMs: Long = System.currentTimeMillis()
    )
}

/**
 * ADR-0023 (spec-43 T6) — the orchestrator of Progress Sync: pull before a
 * resume, throttled push at every save point. Every path degrades silently —
 * no Firebase config, a switched-off toggle or a failing transport leaves the
 * app exactly as it was, never an exception into playback.
 *
 * The rule set lives in [ProgressSyncPolicy]; this class only sequences it:
 *
 *  - **Pull** (`pullBeforeResume`): apply the cloud row iff strictly newer
 *    than the newest server state this device has seen; record its stamp.
 *  - **Push** (`pushAfterSave`): honest moments (pause/completion) go at
 *    once; periodic ticks wait out the pacing window. A successful push's
 *    server stamp becomes this device's sync point.
 */
class ProgressSyncController(
    private val identity: ListenerIdentity,
    private val mirror: ProgressMirror,
    /**
     * Null without Firebase keys — then Progress Sync does not exist and
     * both entry points return immediately.
     */
    private val store: ListenerProgressSyncStore?,
    private val ledger: ProgressSyncLedger,
    private val isEnabled: () -> Boolean = { true },
    /** Wall clock, injectable for deterministic throttle tests. */
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    suspend fun pullBeforeResume(bookId: String): Unit = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext
        val remoteStore = store ?: return@withContext
        val uid = identityUid() ?: return@withContext
        val editionId = mirror.editionIdForSync(bookId) ?: return@withContext

        val remote = remoteStore.pull(uid, editionId)
        val syncedAtServerMs = ledger.lastSyncedServerMs(editionId)
        if (!isEnabled()) return@withContext // the switch stops it mid-flight too
        if (remote == null || !ProgressSyncPolicy.shouldPull(remote, syncedAtServerMs)) {
            return@withContext
        }
        mirror.applyRemoteProgress(bookId, remote)
        ledger.recordSyncedServerMs(editionId, remote.updatedAtServerMs)
    }

    suspend fun pushAfterSave(bookId: String, immediate: Boolean): Unit =
        withContext(Dispatchers.IO) {
            if (!isEnabled()) return@withContext
            val remoteStore = store ?: return@withContext
            val uid = identityUid() ?: return@withContext
            val editionId = mirror.editionIdForSync(bookId) ?: return@withContext
            val local = mirror.progressByEdition(editionId) ?: return@withContext

            if (!ProgressSyncPolicy.shouldPush(nowMs(), ledger.lastPushAttemptMs(editionId), immediate)) {
                return@withContext
            }
            ledger.recordPushAttempt(editionId, nowMs())
            val payload = RemoteListeningState(
                editionId = editionId,
                chapterIndex = local.currentChapterIndex,
                positionSeconds = local.currentPositionSeconds,
                isCompleted = local.isCompleted,
                preferredSpeed = local.preferredSpeed,
                updatedAtServerMs = 0L // stamped by the server at the transport
            )
            // The switch stops it mid-flight too — nothing leaves after it flips.
            if (!isEnabled()) return@withContext
            val serverStamp = remoteStore.push(uid, payload)
            if (serverStamp != null && serverStamp > 0L) {
                val previous = ledger.lastSyncedServerMs(editionId)
                if (previous == null || serverStamp > previous) {
                    ledger.recordSyncedServerMs(editionId, serverStamp)
                }
            }
        }

    /**
     * The listener uid to sync under, or null when there is nothing to sync:
     * a `local-…` profile has no cloud account behind it (offline bootstrap),
     * so uploading would fork the listener's identity.
     */
    private suspend fun identityUid(): String? {
        val profile = runCatching { identity.ensure() }.getOrNull() ?: return null
        return profile.uid.takeUnless { it.startsWith(LocalOnlyIdentity.LOCAL_UID_PREFIX) }
    }
}
