package com.slukhayka.audiobooks.player

/**
 * #1571 — the pure decision behind the player's scrub preview.
 *
 * While a finger is down the chapter slider follows it and the chapter time is
 * rendered from the local fraction; the player (and the progress write that
 * follows every `seekTo`) hears only the release, as ONE seek. This module owns
 * the fraction → position mapping: the label asks [previewSeconds], the release
 * commits [commitPositionMs], and both are fed the SAME exact
 * `playerState.durationMs` — the screen derives the displayed whole seconds from
 * it and hands it to both sides. Because the preview is the commit truncated to
 * the second, the time under the finger is the second the seek lands in; feeding
 * a duration already truncated to seconds on one side would let the two answers
 * differ by a second for durations that are not a whole number of seconds.
 *
 * The player on `main` has ONE adjustable control — the chapter seek. The
 * #1490 «Папір і голос» refresh made the book ruler read-only: «у плеєрі ОДИН
 * регульований контрол — seek розділу … лінія книги стала read-only»
 * (`docs/specs/2026-10-26-ui-refresh-2027.md`). There is therefore no
 * chapter-crossing `Prepare` target here: a scrub can only commit a position
 * inside the current chapter, and [commitPositionMs] answers with it. The screen
 * wiring executes the answer with the same `playerManager.seekTo(...)` call as
 * before — this module never calls the manager.
 *
 * Out-of-range input is clamped, not trusted: a fraction below 0 or above 1,
 * and a non-positive duration (an unknown chapter length — the slider is
 * disabled then, but the rule holds at the door too), both answer with a
 * position the player can accept.
 */
object SeekSession {

    /** The chapter position the drag fraction stands for, in milliseconds. */
    fun commitPositionMs(fraction: Float, chapterDurationMs: Long): Long {
        if (chapterDurationMs <= 0L) return 0L
        return (fraction.coerceIn(0f, 1f) * chapterDurationMs)
            .toLong()
            .coerceIn(0L, chapterDurationMs)
    }

    /**
     * The value the time label shows while the finger is down, in seconds: the
     * same position [commitPositionMs] returns, truncated to the second.
     */
    fun previewSeconds(fraction: Float, chapterDurationMs: Long): Long =
        commitPositionMs(fraction, chapterDurationMs) / 1_000L
}
