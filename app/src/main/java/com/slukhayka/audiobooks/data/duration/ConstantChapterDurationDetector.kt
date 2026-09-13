package com.slukhayka.audiobooks.data.duration

/**
 * #528 — finds the book-level trace a short interstitial leaves behind.
 *
 * When a 52-second stream was read as a chapter, the player wrote that length
 * into the chapter (`persistRealDurationIfKnown`). Fifteen chapters of one book
 * then carried the SAME duration although their files are different — the
 * structural contradiction this detector looks for. A genuinely short chapter
 * is fine; fifteen identical durations over fifteen distinct files is not.
 *
 * Pure, so the repair can be proven without a database.
 */
object ConstantChapterDurationDetector {

    /** Repeats of one duration over distinct files before it counts as a trace. */
    const val MIN_REPEATS = 3

    /**
     * @param chapterDurations one entry per chapter, in any order.
     * @param distinctTracks how many DISTINCT files (url/md5) the book has.
     * @return true when every chapter shares one positive duration while the
     * files are not all the same — the signature of a written-in constant.
     */
    fun looksLikeAConstant(chapterDurations: List<Long>, distinctTracks: Int): Boolean =
        distinctTracks >= MIN_REPEATS && dominantDuration(chapterDurations) != null

    /**
     * The value the interstitial repeats, when enough chapters share it.
     *
     * Requiring «ALL equal» missed the real state measured on device: fourteen
     * of fifteen chapters held 52 s and ONE held its true 3188 s, so nothing was
     * ever repaired. A value shared by [MIN_REPEATS] chapters over distinct
     * files is already implausible for real audio, and it leaves the honestly
     * measured chapter alone.
     *
     * @return the repeated duration, or null when no value repeats enough.
     */
    fun dominantDuration(chapterDurations: List<Long>): Long? {
        val positive = chapterDurations.filter { it > 0L }
        if (positive.isEmpty()) return null
        val counts = positive.groupingBy { it }.eachCount()
        val (value, count) = counts.maxByOrNull { it.value } ?: return null
        return value.takeIf { count >= MIN_REPEATS }
    }
}
