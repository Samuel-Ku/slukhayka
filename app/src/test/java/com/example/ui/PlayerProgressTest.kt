package com.example.ui

import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.ui.screens.calculatePlayerProgress
import com.example.ui.screens.calculateBookSeekTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerProgressTest {
    private val bookId = "player-progress-book"
    private val chapters = listOf(600L, 660L, 720L).mapIndexed { index, duration ->
        ChapterEntity("chapter-$index", bookId, index, "Розділ ${index + 1}", duration, "https://example.invalid/$index.mp3")
    }

    @Test
    fun `maps chapter position boundaries and bookmarks onto book timeline`() {
        val bookmark = BookmarkEntity(
            id = 1,
            bookId = bookId,
            chapterIndex = 1,
            chapterTitle = chapters[1].title,
            timestampSeconds = 60,
            note = "",
            createdAt = 1_700_000_000_000L
        )

        val result = calculatePlayerProgress(
            chapters = chapters,
            currentChapterIndex = 1,
            currentPositionMs = 300_000,
            currentChapterDurationMs = chapters[1].durationSeconds * 1_000,
            bookmarks = listOf(bookmark)
        )

        assertEquals(300f / chapters[1].durationSeconds, result.chapterFraction, 0.0001f)
        assertEquals(2, result.chapterMarkers.size)
        assertEquals(
            (chapters[0].durationSeconds + 60f) / result.bookDurationSeconds,
            result.bookmarkMarkers.single(),
            0.0001f
        )
        assertEquals(chapters[0].durationSeconds + 300, result.bookPositionSeconds)
    }

    @Test
    fun `zero duration chapters use safe evenly spaced markers`() {
        val zeroDuration = chapters.map { it.copy(durationSeconds = 0) }

        val result = calculatePlayerProgress(
            chapters = zeroDuration,
            currentChapterIndex = 0,
            currentPositionMs = 0,
            currentChapterDurationMs = 0,
            bookmarks = emptyList()
        )

        assertEquals(0f, result.chapterFraction)
        assertEquals(0f, result.bookFraction)
        assertEquals(listOf(1f / 3f, 2f / 3f), result.chapterMarkers)
    }

    @Test
    fun `book seek resolves chapter and chapter relative position`() {
        val target = calculateBookSeekTarget(chapters, 0.5f)!!

        assertEquals(1, target.chapterIndex)
        assertEquals(390_000L, target.positionMs)
    }
}
