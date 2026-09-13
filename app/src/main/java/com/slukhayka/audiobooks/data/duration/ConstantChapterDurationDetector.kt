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

    /**
     * @param chapterDurations one entry per chapter, in any order.
     * @param distinctTracks how many DISTINCT files (url/md5) the book has.
     * @return true when every chapter shares one positive duration while the
     * files are not all the same — the signature of a written-in constant.
     */
    fun looksLikeAConstant(chapterDurations: List<Long>, distinctTracks: Int): Boolean {
        if (chapterDurations.size < 2) return false
        if (distinctTracks < 2) return false
        val first = chapterDurations.first()
        if (first <= 0L) return false
        return chapterDurations.all { it == first }
    }
}
