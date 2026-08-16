package com.slukhayka.audiobooks.widget

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.player.PlayerState

/**
 * Immutable state model consumed by [AudiobookGlanceWidget] (spec-21 Track B,
 * restored as spec-22 T4).
 */
data class GlanceWidgetState(
    val hasActiveBook: Boolean = false,
    val bookId: String? = null,
    val title: String = "Слухайка",
    val author: String = "Аудіокниги українською",
    val chapterTitle: String = "Немає активної книги",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionSeconds: Long = 0L,
    val durationSeconds: Long = 0L,
    val progressFraction: Float = 0f,
    val positionFormatted: String = "00:00",
    val durationFormatted: String = "00:00"
)

object WidgetStateMapper {

    fun formatTime(seconds: Long): String {
        val totalSec = seconds.coerceAtLeast(0L)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }

    /**
     * Maps the player's live state (or a fallback book + its stored progress
     * when nothing is loaded) into the widget's immutable state. Pure function
     * — unit-testable without a device.
     */
    fun mapFromPlayerState(
        playerState: PlayerState,
        fallbackBook: AudiobookEntity? = null,
        fallbackProgress: PlaybackProgressEntity? = null,
        fallbackChapters: List<ChapterEntity> = emptyList()
    ): GlanceWidgetState {
        val currentBook = playerState.currentBook ?: fallbackBook
        if (currentBook == null) {
            return GlanceWidgetState(
                hasActiveBook = false,
                title = "Слухайка",
                author = "Аудіокниги українською",
                chapterTitle = "Оберіть аудіокнигу для прослуховування",
                isPlaying = false,
                isBuffering = false,
                currentPositionSeconds = 0L,
                durationSeconds = 0L,
                progressFraction = 0f,
                positionFormatted = "00:00",
                durationFormatted = "00:00"
            )
        }

        val chapters = if (playerState.chapters.isNotEmpty()) playerState.chapters else fallbackChapters
        val chapterIndex = if (playerState.currentBook != null) {
            playerState.currentChapterIndex
        } else {
            fallbackProgress?.currentChapterIndex ?: 0
        }
        val currentChapter = chapters.getOrNull(chapterIndex)
        val chapterTitle = currentChapter?.title ?: "Розділ ${chapterIndex + 1}"

        val posSec = if (playerState.currentBook != null) {
            playerState.currentPositionMs / 1000L
        } else {
            fallbackProgress?.currentPositionSeconds ?: 0L
        }

        val durSec = if (playerState.currentBook != null && playerState.durationMs > 0L) {
            playerState.durationMs / 1000L
        } else {
            currentChapter?.durationSeconds ?: 0L
        }

        val progress = if (durSec > 0L) {
            (posSec.toFloat() / durSec.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        return GlanceWidgetState(
            hasActiveBook = true,
            bookId = currentBook.id,
            title = currentBook.title.ifBlank { "Слухайка" },
            author = currentBook.author?.ifBlank { "Слухайка" } ?: "Слухайка",
            chapterTitle = chapterTitle,
            isPlaying = playerState.isPlaying,
            isBuffering = playerState.isBuffering,
            currentPositionSeconds = posSec,
            durationSeconds = durSec,
            progressFraction = progress,
            positionFormatted = formatTime(posSec),
            durationFormatted = formatTime(durSec)
        )
    }
}
