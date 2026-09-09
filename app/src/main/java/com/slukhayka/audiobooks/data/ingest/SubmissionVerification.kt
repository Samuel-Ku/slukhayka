package com.slukhayka.audiobooks.data.ingest

import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0035 / #604 — the local verification gate of a submitted source.
 *
 * A submission is VERIFIED only after the player's REAL `playing` event fired
 * for audio fetched from it — the same fact the Edition Availability
 * Assertion uses (spec-32/42: only an actual Player `playing` event creates a
 * positive verdict; a prepared-but-never-played probe never does). The memo
 * is in-memory and per-process (the [SmartRetryMemo] / [AutoRepairMemo]
 * precedent): the persisted publication write-back is the NEXT ticket's job
 * (#605), so no schema change rides on this slice.
 */
class SubmissionVerification(private val clock: () -> Long = System::currentTimeMillis) {

    private val verifiedAtBySource = ConcurrentHashMap<String, Long>()

    /** True when a real playback verdict arrived for this source. */
    fun isVerified(sourceId: String): Boolean = verifiedAtBySource.containsKey(sourceId)

    /** The moment the verdict fired, or null when never verified. */
    fun verifiedAt(sourceId: String): Long? = verifiedAtBySource[sourceId]

    /**
     * Records the verdict. ONLY a real `playing` event may pass `true` here —
     * the caller (the player verdict seam) is the sole honest source of that
     * fact. `false` never clears a positive verdict (once played, always
     * verified for the process).
     */
    fun record(sourceId: String, actualPlaybackStarted: Boolean) {
        if (actualPlaybackStarted) verifiedAtBySource.putIfAbsent(sourceId, clock())
    }
}