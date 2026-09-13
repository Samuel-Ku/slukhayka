package com.slukhayka.audiobooks.data.duration

/**
 * #528 — the second repair rule: a duration that is physically impossible for
 * the stream it belongs to.
 *
 * The constant-duration detector catches multi-chapter books, but a ONE-chapter
 * book carrying the interstitial's length has no sibling to compare with
 * («Планета туману»: one chapter, 52 s, and a track that really is ~51 MB).
 * Size is the signal available there: 51 MB at 128 kbps is ~53 minutes, so a
 * 52-second claim cannot describe that file.
 *
 * Pure, so the rule is provable without a player or a database.
 */
object StreamSizeDurationCheck {

    /** A stored duration this many times shorter than the size implies is a lie. */
    const val IMPLAUSIBLE_RATIO_DENOMINATOR = 10L

    /**
     * @param durationSeconds the duration stored for the chapter.
     * @param contentLength the stream's size in bytes, or 0 when unknown.
     * @param bitrateBps the frames' CBR bitrate.
     * @return true when [durationSeconds] cannot describe a stream of that size.
     */
    fun impliesImpossibleShort(
        durationSeconds: Long,
        contentLength: Long,
        bitrateBps: Long = 128_000L
    ): Boolean {
        if (durationSeconds <= 0L || contentLength <= 0L || bitrateBps <= 0L) return false
        val implied = contentLength * 8L / bitrateBps
        return durationSeconds < implied / IMPLAUSIBLE_RATIO_DENOMINATOR
    }
}
