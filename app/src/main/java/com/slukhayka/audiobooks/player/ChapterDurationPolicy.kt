package com.slukhayka.audiobooks.player

/**
 * #528 — the rule that keeps a short interstitial from poisoning a chapter.
 *
 * The player writes the duration it measured (`persistRealDurationIfKnown`).
 * A 52-second interstitial read as a 28-minute chapter overwrote 16 chapters
 * with 52 s, and the player then stopped at that mark for every listener. A
 * real chapter never collapses like that, so a collapse is evidence the stream
 * was not the book: keep the known-good value.
 *
 * Pure, so the rule is provable without a player.
 */
object ChapterDurationPolicy {

    /** A measurement this much shorter than the known value is not the book. */
    const val SHRINK_DENOMINATOR = 2L

    /** @return true when [measuredSeconds] may replace [knownSeconds]. */
    fun shouldAccept(knownSeconds: Long, measuredSeconds: Long): Boolean {
        if (measuredSeconds <= 0L) return false
        if (knownSeconds <= 0L) return true      // nothing known yet — take it
        return measuredSeconds >= knownSeconds / SHRINK_DENOMINATOR
    }
}
