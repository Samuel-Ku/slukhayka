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

    /**
     * A stored duration this many times shorter than the size implies is a lie.
     *
     * Measured on the observed book: with 10 the check missed the two SHORTEST
     * tracks (≈6 min and ≈4.7 min implied), because 52 s is only ~7× and ~5×
     * below them. At 4 every chapter of that book is caught, while the honest
     * controls still hold — a real 1701 s against its own size, and a genuine
     * 30-second chapter whose small file implies 30 s.
     */
    const val IMPLAUSIBLE_RATIO_DENOMINATOR = 4L

    /**
     * @param durationSeconds the duration stored for the chapter.
     * @param contentLength the stream's size in bytes, or 0 when unknown.
     * @param bitrateBps the frames' CBR bitrate.
     * @return true when [durationSeconds] cannot describe a stream of that size.
     */
    fun impliesImpossibleShort(
        durationSeconds: Long,
        contentLength: Long,
        bitrateBps: Long = DEFAULT_BITRATE_BPS
    ): Boolean {
        if (durationSeconds <= 0L || contentLength <= 0L || bitrateBps <= 0L) return false
        val implied = impliedSeconds(contentLength, bitrateBps)
        return durationSeconds < implied / IMPLAUSIBLE_RATIO_DENOMINATOR
    }

    /**
     * How long an audio stream of [contentLength] bytes can last at
     * [bitrateBps]. Exposed so the playback instrumentation can state the
     * number it saw without repeating the formula.
     */
    fun impliedSeconds(contentLength: Long, bitrateBps: Long = DEFAULT_BITRATE_BPS): Long {
        if (contentLength <= 0L || bitrateBps <= 0L) return 0L
        return contentLength * 8L / bitrateBps
    }

    /** The CBR bitrate the size heuristic assumes when nothing else is known. */
    const val DEFAULT_BITRATE_BPS = 128_000L

    /**
     * #528 — the MIRROR of [IMPLAUSIBLE_RATIO_DENOMINATOR], and the one that
     * faces the CDN during playback: is the BODY impossibly small for the
     * duration we already know?
     *
     * The two cases are not the same shape. [impliesImpossibleShort] catches
     * *metadata shorter than the file* — a lone 52-second chapter row whose
     * download really is 51 MB. Playback substitution is the opposite: the
     * chapter is known to be 1701 s and the CDN hands back a 52-second ad.
     * Using the first rule for the second case would never fire at all.
     *
     * The factor is deliberately looser than the metadata rule's 4, because
     * this one has a failure mode that rule never had: it gates PLAYBACK. The
     * implied seconds assume 128 kbps, and audiobooks are speech, commonly
     * 32–64 kbps — at 24 kbps a perfectly honest chapter would already look
     * ~5× "too small" against the assumption. Requiring an 8× shortfall
     * tolerates the bitrate being wrong by up to 8× (real bitrates down to
     * 16 kbps) while still catching the observed substitution with room to
     * spare: 52 s against 1701 s is 32× short. A guard that refuses an honest
     * file is worse than one that misses a bad one.
     */
    const val IMPLAUSIBLE_BODY_DENOMINATOR = 8L

    /**
     * @param durationSeconds the duration we already know for this chapter.
     * @param contentLength the served body's size in bytes, or 0 when unknown.
     * @param bitrateBps the assumed CBR bitrate.
     * @return true when the body cannot possibly carry [durationSeconds] of audio.
     */
    fun impliesImpossibleSmallBody(
        durationSeconds: Long,
        contentLength: Long,
        bitrateBps: Long = DEFAULT_BITRATE_BPS
    ): Boolean {
        if (durationSeconds <= 0L || contentLength <= 0L || bitrateBps <= 0L) return false
        val implied = impliedSeconds(contentLength, bitrateBps)
        return implied < durationSeconds / IMPLAUSIBLE_BODY_DENOMINATOR
    }
}
