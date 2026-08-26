package com.slukhayka.audiobooks.data.listening

import android.content.Context

/**
 * ADR-0023 (spec-43 T6) — what this device already knows about the cloud's
 * listening_state documents, per Edition: the newest server timestamp it has
 * SEEN (pull-applied or pushed) and the last push attempt's wall-clock mark
 * (pacing only — never arbitration). Small prefs ledger; no schema impact on
 * the Room Listening State.
 */
interface ProgressSyncLedger {
    fun lastSyncedServerMs(editionId: String): Long?
    fun recordSyncedServerMs(editionId: String, serverMs: Long)
    fun lastPushAttemptMs(editionId: String): Long?
    fun recordPushAttempt(editionId: String, atMs: Long)
}

class SharedPreferencesProgressSyncLedger(context: Context) : ProgressSyncLedger {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun lastSyncedServerMs(editionId: String): Long? =
        prefs.getLong(syncKey(editionId), -1L).takeIf { it >= 0L }

    override fun recordSyncedServerMs(editionId: String, serverMs: Long) {
        prefs.edit().putLong(syncKey(editionId), serverMs).apply()
    }

    override fun lastPushAttemptMs(editionId: String): Long? =
        prefs.getLong(attemptKey(editionId), -1L).takeIf { it >= 0L }

    override fun recordPushAttempt(editionId: String, atMs: Long) {
        prefs.edit().putLong(attemptKey(editionId), atMs).apply()
    }

    private fun syncKey(editionId: String) = "synced_$editionId"
    private fun attemptKey(editionId: String) = "attempt_$editionId"

    private companion object {
        const val FILE = "progress_sync_ledger"
    }
}
