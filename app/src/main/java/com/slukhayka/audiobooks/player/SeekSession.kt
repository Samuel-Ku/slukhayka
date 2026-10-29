package com.slukhayka.audiobooks.player

/**
 * #1571 — the pure decisions behind the player's scrub preview.
 *
 * While a finger is down a slider follows it and its time label is rendered from
 * the local fraction; the player (and the progress write that follows every
 * `seekTo`) hears only the release, as ONE seek. `release/1.6.2` keeps the
 * 1.6.x player with TWO adjustable sliders, so this module answers for both:
 *
 * - [commitPositionMs] is the chapter release: the exact position the chapter
 *   slider's seek lands on, mapped against the same `playerState.durationMs`
 *   the label's whole seconds are derived from, so the time under the finger is
 *   the second the seek lands in;
 * - [previewSeconds] is that chapter label value, in seconds;
 * - [bookPreviewSeconds] is the whole-book label while its own ruler is down.
 *   The release hands the same fraction to the 1.6.x `onBookSeek` wiring, which
 *   maps it once through `calculateBookSeekTarget` — one seek, or one chapter
 *   re-prepare across a chapter boundary, exactly as that player already
 *   decided; this module never calls the manager.
 *
 * Out-of-range input is clamped, not trusted: a fraction below 0 or above 1,
 * and a non-positive duration (an unknown length — the sliders are disabled
 * then, but the rule holds at the door too), both answer with a position the
 * player can accept.
 */
object SeekSession {

    /** The chapter position the chapter drag fraction stands for, in milliseconds. */
    fun commitPositionMs(fraction: Float, chapterDurationMs: Long): Long {
        if (chapterDurationMs <= 0L) return 0L
        return (fraction.coerceIn(0f, 1f) * chapterDurationMs)
            .toLong()
            .coerceIn(0L, chapterDurationMs)
    }

    /**
     * The chapter label value while the finger is down, in seconds: the same
     * position [commitPositionMs] returns, truncated to the second.
     */
    fun previewSeconds(fraction: Float, chapterDurationMs: Long): Long =
        commitPositionMs(fraction, chapterDurationMs) / 1_000L

    /** The whole-book label value while the book ruler is down, in seconds. */
    fun bookPreviewSeconds(fraction: Float, bookDurationSeconds: Long): Long {
        if (bookDurationSeconds <= 0L) return 0L
        return (fraction.coerceIn(0f, 1f) * bookDurationSeconds)
            .toLong()
            .coerceIn(0L, bookDurationSeconds)
    }
}
