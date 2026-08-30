package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.player.PlaybackErrorKind
import com.slukhayka.audiobooks.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPlaybackVerdictTest {
    private val book = AudiobookEntity(
        id = "book",
        title = "Книга",
        author = "Автор",
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = ""
    )

    @Test fun `only real playback completes recovery`() = runTest {
        val states = MutableStateFlow(PlayerState(currentBook = book, currentChapterIndex = 2))
        launch { states.update { it.copy(isPlaying = true) } }
        assertTrue(awaitRecoveryPlaybackVerdict(states, "book", 2))
    }

    @Test fun `unavailable stream fails recovery instead of reporting success`() = runTest {
        val states = MutableStateFlow(
            PlayerState(currentBook = book, currentChapterIndex = 2, errorKind = PlaybackErrorKind.UNAVAILABLE)
        )
        assertFalse(awaitRecoveryPlaybackVerdict(states, "book", 2))
    }
}
