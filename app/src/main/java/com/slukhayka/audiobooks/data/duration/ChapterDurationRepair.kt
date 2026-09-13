package com.slukhayka.audiobooks.data.duration

import android.content.SharedPreferences
import com.slukhayka.audiobooks.data.db.AudiobookDao

/**
 * #528 — the one-shot pass that repairs chapter durations a short interstitial
 * already poisoned, so the existing enrichment can re-measure them.
 *
 * The guard (`ChapterDurationPolicy`) protects the future; this pass repairs the
 * past. It reads only the database — no probing, no network — because the size
 * rule needs a stream size and would turn every start into a batch of requests.
 * Single-chapter books are therefore left to the enrichment path, which probes
 * anyway; this pass handles the multi-chapter trace, which is free to detect.
 *
 * Resetting means `durationSeconds = 0` («unknown»): this pass computes nothing
 * itself, it only removes a value that cannot be true.
 */
class ChapterDurationRepair(
    private val dao: AudiobookDao,
    private val prefs: SharedPreferences,
    private val doneKey: String = KEY_DONE
) {

    /** @return how many chapters were reset, or 0 when already done. */
    suspend fun runOnce(): Int {
        if (prefs.getBoolean(doneKey, false)) return 0
        val chapters = dao.getAllChaptersOnce()
        if (chapters.isEmpty()) return 0

        var reset = 0
        for ((_, bookChapters) in chapters.groupBy { it.bookId }) {
            val editionId = bookChapters.firstNotNullOfOrNull { it.editionId } ?: continue
            val durations = bookChapters.associate { it.id to it.durationSeconds }
            val distinctTracks = dao.countDistinctTracksForEdition(editionId)
            for (id in ChapterDurationRepairPlan.chaptersToReset(durations, distinctTracks)) {
                dao.updateChapterDuration(id, 0L)
                reset++
            }
        }

        prefs.edit().putBoolean(doneKey, true).apply()
        return reset
    }

    companion object {
        /**
         * Own flag: the seed's «once per install» must not gate the repair.
         *
         * KNOWN GAP (#528, measured on device): resetting a chapter to 0 hands it
         * to [com.slukhayka.audiobooks.player.ChapterDurationPolicy] in the one
         * state that policy accepts unconditionally — so a short interstitial can
         * re-poison it, and this one-shot flag means the repair will not run
         * again. The pass should become repeatable (its own condition is already
         * idempotent) once the guard can tell a reset chapter from an unknown one.
         */
        const val KEY_DONE = "chapter_duration_repair_v1_done"
    }
}
