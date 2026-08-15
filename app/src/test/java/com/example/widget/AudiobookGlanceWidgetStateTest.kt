package com.example.widget

import com.example.data.db.AudiobookEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-21 Track B / spec-22 T4 — the widget's state mapping (PlayerState +
 * optional fallback → GlanceWidgetState) is a pure function, pinned on the
 * JVM without a device.
 */
class AudiobookGlanceWidgetStateTest {

    @Test
    fun mapFromPlayerState_withNoActiveBookAndNoFallback_returnsEmptyState() {
        val playerState = PlayerState()
        val widgetState = WidgetStateMapper.mapFromPlayerState(playerState)

        assertFalse(widgetState.hasActiveBook)
        assertEquals("Слухайка", widgetState.title)
        assertEquals("00:00", widgetState.positionFormatted)
        assertEquals("00:00", widgetState.durationFormatted)
        assertEquals(0f, widgetState.progressFraction, 0.001f)
    }

    @Test
    fun mapFromPlayerState_withActivePlayingBook_returnsActiveState() {
        val book = AudiobookEntity(
            id = "test_book_1",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "Опанас Маркович",
            description = "Поетична збірка Тараса Шевченка",
            coverDrawableRes = 0,
            genre = "Класика",
            sourceUrl = "https://4read.org/kobzar.html"
        )
        val chapters = listOf(
            ChapterEntity(
                id = "ch_0",
                bookId = "test_book_1",
                chapterIndex = 0,
                title = "Думи мої, думи мої",
                durationSeconds = 600L,
                streamUrl = "https://example.com/ch0.mp3"
            )
        )
        val playerState = PlayerState(
            currentBook = book,
            chapters = chapters,
            currentChapterIndex = 0,
            currentPositionMs = 125_000L, // 2m 5s
            durationMs = 600_000L, // 10m
            isPlaying = true
        )

        val widgetState = WidgetStateMapper.mapFromPlayerState(playerState)

        assertTrue(widgetState.hasActiveBook)
        assertEquals("test_book_1", widgetState.bookId)
        assertEquals("Кобзар", widgetState.title)
        assertEquals("Тарас Шевченко", widgetState.author)
        assertEquals("Думи мої, думи мої", widgetState.chapterTitle)
        assertTrue(widgetState.isPlaying)
        assertEquals(125L, widgetState.currentPositionSeconds)
        assertEquals(600L, widgetState.durationSeconds)
        assertEquals("02:05", widgetState.positionFormatted)
        assertEquals("10:00", widgetState.durationFormatted)
        assertEquals(125f / 600f, widgetState.progressFraction, 0.01f)
    }

    @Test
    fun mapFromPlayerState_withFallbackProgress_returnsResumableState() {
        val playerState = PlayerState(currentBook = null)
        val fallbackBook = AudiobookEntity(
            id = "fallback_book",
            title = "Тіні забутих предків",
            author = "Михайло Коцюбинський",
            narrator = "Гнат Хоткевич",
            description = "Повість про гуцулів",
            coverDrawableRes = 0,
            genre = "Класика",
            sourceUrl = "https://4read.org/tini.html"
        )
        val fallbackChapters = listOf(
            ChapterEntity(
                id = "ch_1",
                bookId = "fallback_book",
                chapterIndex = 0,
                title = "Частина 1",
                durationSeconds = 1800L,
                streamUrl = "https://example.com/tini1.mp3"
            )
        )
        val fallbackProgress = PlaybackProgressEntity(
            bookId = "fallback_book",
            currentChapterIndex = 0,
            currentPositionSeconds = 300L,
            lastListenedAt = 1000L
        )

        val widgetState = WidgetStateMapper.mapFromPlayerState(
            playerState = playerState,
            fallbackBook = fallbackBook,
            fallbackProgress = fallbackProgress,
            fallbackChapters = fallbackChapters
        )

        assertTrue(widgetState.hasActiveBook)
        assertEquals("fallback_book", widgetState.bookId)
        assertEquals("Тіні забутих предків", widgetState.title)
        assertEquals("Михайло Коцюбинський", widgetState.author)
        assertEquals("Частина 1", widgetState.chapterTitle)
        assertFalse(widgetState.isPlaying)
        assertEquals(300L, widgetState.currentPositionSeconds)
        assertEquals(1800L, widgetState.durationSeconds)
        assertEquals("05:00", widgetState.positionFormatted)
        assertEquals("30:00", widgetState.durationFormatted)
    }

    @Test
    fun formatTime_handlesHoursAndZero() {
        assertEquals("00:00", WidgetStateMapper.formatTime(0))
        assertEquals("00:05", WidgetStateMapper.formatTime(5))
        assertEquals("1:00:00", WidgetStateMapper.formatTime(3600))
        assertEquals("2:04:09", WidgetStateMapper.formatTime(2 * 3600 + 4 * 60 + 9))
        // Negative input degrades to zero, never to a broken string.
        assertEquals("00:00", WidgetStateMapper.formatTime(-10))
    }
}
