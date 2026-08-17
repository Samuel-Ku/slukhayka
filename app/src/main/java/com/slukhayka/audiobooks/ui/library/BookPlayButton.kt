package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity

/**
 * #40 decision 1 — the book page's main button shows the book's state instead
 * of a bare «Play»:
 *
 * - [Playing]: this book is actually playing right now — priority label.
 * - [Unstarted]: no stored progress — «Слухати».
 * - [InProgress]: progress inside the book — «Продовжити з HH:MM:SS».
 * - [Finished]: position at/after the end (spec-16 T4 rule) — «Почати спочатку».
 *
 * Pure JVM: the screen only feeds values in, never formats state itself.
 */
sealed interface BookPlayState {
    data object Playing : BookPlayState
    data object Unstarted : BookPlayState
    data class InProgress(val resumePositionSeconds: Long) : BookPlayState
    data object Finished : BookPlayState
}

/**
 * Maps the book's playback facts onto the four button states. The playing
 * state wins over everything while this book is the player's current book;
 * otherwise the position decides, never the raw existence of a progress row
 * (a row at position 0 is still unstarted).
 */
fun bookPlayState(
    isPlayingThisBook: Boolean,
    progress: PlaybackProgressEntity?,
    cumulativePositionSeconds: Long,
    totalDurationSeconds: Long
): BookPlayState = when {
    isPlayingThisBook -> BookPlayState.Playing
    progress == null -> BookPlayState.Unstarted
    totalDurationSeconds > 0L && cumulativePositionSeconds >= totalDurationSeconds ->
        BookPlayState.Finished
    cumulativePositionSeconds > 0L -> BookPlayState.InProgress(cumulativePositionSeconds)
    else -> BookPlayState.Unstarted
}

/**
 * The button's label for a state. `formatTime` renders the HH:MM:SS of the
 * resume position (injected so the mapper stays Android-free and testable).
 */
fun bookPlayLabel(state: BookPlayState, formatTime: (Long) -> String): String = when (state) {
    // Spec-27 (#204): Ukrainian everywhere — the playing state reads «Грає».
    BookPlayState.Playing -> "Грає"
    BookPlayState.Unstarted -> "Слухати"
    is BookPlayState.InProgress -> "Продовжити з ${formatTime(state.resumePositionSeconds)}"
    BookPlayState.Finished -> "Почати спочатку"
}

/**
 * The cumulative position inside the book and the authoritative total — the
 * same rule the library card (spec-16 T4) uses: the site-provided book total
 * is authoritative, unknown chapter durations are spread over the remainder,
 * and a book without a total falls back to the sum of its chapter durations.
 * Returns `(cumulative, total)` — both 0 when there is no progress.
 */
fun bookPositionAndTotal(
    chapters: List<ChapterEntity>,
    progress: PlaybackProgressEntity?,
    bookTotalDurationSeconds: Long
): Pair<Long, Long> {
    if (progress == null) return 0L to 0L
    val durations = effectiveChapterDurations(
        chapters = chapters,
        currentChapterIndex = progress.currentChapterIndex,
        currentChapterDurationMs = 0L,
        bookTotalDurationSeconds = bookTotalDurationSeconds
    )
    val total = bookTotalDurationSeconds.takeIf { it > 0L } ?: durations.sum()
    val beforeChapter = durations
        .take(progress.currentChapterIndex.coerceAtLeast(0))
        .sum()
    return (beforeChapter + progress.currentPositionSeconds) to total
}