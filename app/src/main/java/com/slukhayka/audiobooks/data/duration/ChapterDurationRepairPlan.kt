package com.slukhayka.audiobooks.data.duration

/**
 * #528 — decides WHICH chapters a repair pass must reset, so the wiring stays
 * trivial and the decision stays testable.
 *
 * Two already-merged rules do the judging:
 * - [ConstantChapterDurationDetector] — a multi-chapter book whose chapters all
 *   share one duration although the files differ (the interstitial's trace);
 * - [StreamSizeDurationCheck] — a duration that cannot describe its own stream
 *   (the single-chapter case, where there is no sibling to compare with).
 *
 * Resetting to 0 means «unknown»: the existing enrichment re-measures the
 * chapter through its own probe path. This plan computes nothing itself.
 */
object ChapterDurationRepairPlan {

    /**
     * @param chapters chapter id → stored duration, in any order.
     * @param distinctTracks how many DISTINCT files the book has.
     * @param contentLengths chapter id → stream size in bytes, where known.
     * @return the ids to reset; empty when the book looks honest.
     */
    fun chaptersToReset(
        chapters: Map<String, Long>,
        distinctTracks: Int,
        contentLengths: Map<String, Long> = emptyMap()
    ): List<String> {
        if (chapters.isEmpty()) return emptyList()
        val durations = chapters.values.toList()

        val dominant = ConstantChapterDurationDetector.dominantDuration(durations)
        if (dominant != null && distinctTracks >= ConstantChapterDurationDetector.MIN_REPEATS) {
            // Reset only the chapters carrying the repeated value: a correctly
            // measured sibling (e.g. 3188 s beside fourteen 52 s) stays.
            return chapters.filterValues { it == dominant }.keys.toList()
        }

        return chapters.filter { (id, duration) ->
            val size = contentLengths[id] ?: return@filter false
            StreamSizeDurationCheck.impliesImpossibleShort(duration, size)
        }.keys.toList()
    }
}
