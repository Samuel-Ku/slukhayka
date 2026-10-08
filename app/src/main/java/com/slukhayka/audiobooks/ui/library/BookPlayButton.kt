package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.listening.bookProgress
import com.slukhayka.audiobooks.data.listening.isBookFinished

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
    // #1174 — the end-of-book boundary is the shared one, never a local sum:
    // a label that disagrees with the «Завершені» shelf is a bug either way.
    isBookFinished(
        completedManually = false,
        cumulativePositionSeconds = cumulativePositionSeconds,
        totalDurationSeconds = totalDurationSeconds
    ) -> BookPlayState.Finished
    cumulativePositionSeconds > 0L -> BookPlayState.InProgress(cumulativePositionSeconds)
    else -> BookPlayState.Unstarted
}

/**
 * The button's label for a state. `formatTime` renders the HH:MM:SS of the
 * resume position (injected so the mapper stays Android-free and testable).
 */
fun bookPlayLabel(state: BookPlayState, formatTime: (Long) -> String): String = when (state) {
    // The playing state reads «Пауза» — the main button is an ACTION control:
    // while this book is on the player, tapping it pauses (it toggles), so the
    // label names the action, not the state (2026-08-17 bug report: the old
    // «Грає» label re-played the book and could never pause).
    BookPlayState.Playing -> "Пауза"
    BookPlayState.Unstarted -> "Слухати"
    is BookPlayState.InProgress -> "Продовжити з ${formatTime(state.resumePositionSeconds)}"
    BookPlayState.Finished -> "Почати спочатку"
}

/**
 * The cumulative position inside the book and the authoritative total, as one
 * `(cumulative, total)` pair — the UI's face of the shared rule in
 * [com.slukhayka.audiobooks.data.listening.bookProgress], kept because the book
 * page's surfaces read the pair. Both are 0 when there is no progress: the
 * button is «Слухати» and no position exists to show.
 */
fun bookPositionAndTotal(
    chapters: List<ChapterEntity>,
    progress: PlaybackProgressEntity?,
    bookTotalDurationSeconds: Long
): Pair<Long, Long> {
    if (progress == null) return 0L to 0L
    val facts = bookProgress(chapters, progress, bookTotalDurationSeconds)
    return facts.cumulativePositionSeconds to facts.totalDurationSeconds
}