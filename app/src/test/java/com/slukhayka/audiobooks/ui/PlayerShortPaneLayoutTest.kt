package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.screens.PlayerScreenContent
import com.slukhayka.audiobooks.ui.screens.calculatePlayerProgress
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w905dp-h400dp-420dpi", sdk = [36])
class PlayerShortPaneLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val book = AudiobookEntity(
        id = "short-pane-book", title = "Книжка з довгою назвою для вечірнього слухання",
        author = "Автор", narrator = "Читець", description = "", coverDrawableRes = 0,
        genre = "", sourceUrl = "", totalDurationSeconds = 5400, totalChapters = 1,
    )
    private val chapters = listOf(
        ChapterEntity(id = "short-pane-chapter", bookId = book.id, chapterIndex = 0,
            title = "Поточний розділ", durationSeconds = 5400)
    )

    @Test
    fun landscapePaneStartsWithSeekAndTransportAtNormalText() = checkPane(1f)

    @Test
    fun landscapePaneStartsWithSeekAndTransportAtTwoHundredPercent() = checkPane(2f)

    @Test
    fun landscapePaneKeepsTheBookmarkAndTransportVisibleAtTwoHundredPercent() = checkPane(
        fontScale = 2f,
        bookmark = BookmarkEntity(
            id = 1L, bookId = book.id, chapterIndex = 0,
            chapterTitle = chapters.single().title, timestampSeconds = 1800L,
            note = "Повернутися до фрагмента", createdAt = 1L,
        ),
    )

    private fun checkPane(fontScale: Float, bookmark: BookmarkEntity? = null) {
        var playClicks = 0
        var bookmarkClicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(460.dp, 352.dp)) {
                        PlayerScreenContent(
                            playerState = PlayerState(currentBook = book, chapters = chapters,
                                currentPositionMs = 3_600_000, durationMs = 5_400_000,
                                isOfflineMode = true),
                            book = book,
                            currentChapterTitle = chapters.single().title,
                            progress = calculatePlayerProgress(chapters, 0, 3_600_000, 5_400_000, listOfNotNull(bookmark)),
                            artworkAccent = null,
                            onArtworkLoaded = {}, onDismiss = {}, onToggleFavorite = {},
                            onToggleDebug = {}, onSeek = {}, onBookSeek = {},
                            onPreviousChapter = {}, onBack = {}, onPlayPause = { playClicks++ },
                            onForward = {}, onNextChapter = {}, onUndoSeek = {},
                            onSpeed = {}, onTimer = {}, onBookmark = {}, onChapters = {},
                            lastBookmarkTarget = bookmark,
                            onJumpToBookmark = {
                                assertEquals(bookmark, it)
                                bookmarkClicks++
                            },
                        )
                    }
                }
            }
        }
        val viewport = compose.onNodeWithTag("full_player_screen", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val bookmarkDescription = bookmark?.let {
            RuntimeEnvironment.getApplication().getString(
                R.string.a11y_player_bookmark_jump, MainViewModel.formatTime(it.timestampSeconds)
            )
        }
        val initialControls = listOf("player_progress_slider", "player_previous_button", "player_rewind_button",
            "player_play_pause_button", "player_forward_button", "player_next_button")
            .map { it to compose.onNodeWithTag(it, useUnmergedTree = true) } +
            if (bookmarkDescription != null) listOf(
                "last bookmark" to compose.onNodeWithContentDescription(bookmarkDescription, useUnmergedTree = true)
            ) else emptyList()
        initialControls.forEach { (tag, node) ->
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$tag must be initially inside $viewport at fontScale=$fontScale, got $bounds",
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom + 1.dp &&
                    bounds.left >= viewport.left && bounds.right <= viewport.right + 1.dp)
            node.assertIsDisplayed()
        }
        listOf("1:00:00", " / 1:30:00").forEach { time ->
            val node = compose.onNodeWithText(time, useUnmergedTree = true)
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("chapter time must fit inside the pane at fontScale=$fontScale: $bounds",
                bounds.left >= viewport.left && bounds.right <= viewport.right + 1.dp)
            node.assertIsDisplayed()
        }
        if (bookmarkDescription != null) {
            compose.onNodeWithContentDescription(bookmarkDescription).performClick()
            assertEquals(1, bookmarkClicks)
        }
        compose.onNodeWithTag("player_play_pause_button").performClick()
        assertEquals(1, playClicks)
        compose.onNodeWithTag("chapters_chip", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}
