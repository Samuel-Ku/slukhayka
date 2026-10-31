package com.slukhayka.audiobooks.data.duration

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
) {

    /**
     * @return how many chapters were reset; 0 when the data looks honest.
     *
     * REPEATABLE on purpose (#528). The pass used to run once per install, but
     * measurement showed why that is wrong: resetting a chapter hands it to
     * [com.slukhayka.audiobooks.player.ChapterDurationPolicy] in the one state
     * that policy accepts unconditionally, so a short interstitial re-poisons it
     * — and a one-shot flag would never repair it again. The rule itself is
     * idempotent (after a reset the durations are no longer a constant), so
     * running on every start breaks that cycle at the cost of one query.
     */
    suspend fun runOnce(): Int {
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

        return reset
    }

}
