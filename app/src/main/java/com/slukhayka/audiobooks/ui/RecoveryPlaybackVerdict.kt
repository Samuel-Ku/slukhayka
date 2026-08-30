package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.player.PlaybackErrorKind
import com.slukhayka.audiobooks.player.PlayerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A recovered URL is successful only after the player actually starts it. */
internal suspend fun awaitRecoveryPlaybackVerdict(
    states: Flow<PlayerState>,
    bookId: String,
    chapterIndex: Int,
    timeoutMs: Long = 10_000L
): Boolean = withTimeoutOrNull(timeoutMs) {
    states.first { state ->
        state.currentBook?.id == bookId && state.currentChapterIndex == chapterIndex &&
            (state.isPlaying || state.errorKind == PlaybackErrorKind.UNAVAILABLE)
    }.isPlaying
} ?: false
