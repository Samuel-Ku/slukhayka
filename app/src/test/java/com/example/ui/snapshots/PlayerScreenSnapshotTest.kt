package com.example.ui.snapshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.player.PlayerState
import com.example.ui.screens.PlayerScreenContent
import com.example.ui.screens.calculatePlayerProgress
import com.example.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PlayerScreenSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = AudiobookEntity(
        id = "player-snapshot-book",
        title = "Нейромант",
        author = "Вільям Гібсон",
        narrator = "Олександр Завальський",
        description = "",
        coverDrawableRes = 0,
        genre = "Кіберпанк",
        sourceUrl = "https://example.invalid/book",
        isDownloaded = true,
        totalDurationSeconds = 1_980,
        totalChapters = 3,
        isFavorite = true
    )
    private val chapters = listOf(600L, 660L, 720L).mapIndexed { index, duration ->
        ChapterEntity(
            id = "chapter-$index",
            bookId = book.id,
            chapterIndex = index,
            title = "Розділ ${index + 1}. Зустріч у Чіба-сіті",
            durationSeconds = duration,
            streamUrl = "https://example.invalid/chapter-$index.mp3"
        )
    }
    private val bookmark = BookmarkEntity(
        id = 1,
        bookId = book.id,
        chapterIndex = 1,
        chapterTitle = chapters[1].title,
        timestampSeconds = 120,
        note = "Ключова сцена",
        createdAt = 1_700_000_000_000L
    )
    private val state = PlayerState(
        currentBook = book,
        chapters = chapters,
        currentChapterIndex = 1,
        isPlaying = true,
        currentPositionMs = 320_000,
        durationMs = chapters[1].durationSeconds * 1_000,
        playbackSpeed = 1.25f,
        isOfflineMode = true
    )
    private val progress = calculatePlayerProgress(
        chapters,
        state.currentChapterIndex,
        state.currentPositionMs,
        state.durationMs,
        listOf(bookmark)
    )

    @Test
    fun full_player_dark() {
        setPlayerContent()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/player_redesign_dark.png")
    }

    @Test
    fun transport_and_quick_tools_are_accessible_and_wired() {
        var playClicks = 0
        var speedClicks = 0
        setPlayerContent(onPlayPause = { playClicks++ }, onSpeed = { speedClicks++ })

        composeTestRule.onNodeWithContentDescription("Попередній розділ").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Назад на 15 секунд").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("player_play_pause_button").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeTestRule.onNodeWithContentDescription("Вперед на 30 секунд").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Наступний розділ").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("speed_chip").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeTestRule.onNodeWithTag("sleep_timer_chip").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("add_bookmark_chip").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("chapters_chip").assertIsDisplayed().assertHeightIsAtLeast(48.dp)

        assertEquals(1, playClicks)
        assertEquals(1, speedClicks)
    }

    private fun setPlayerContent(
        onPlayPause: () -> Unit = {},
        onSpeed: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    PlayerScreenContent(
                        playerState = state,
                        book = book,
                        currentChapterTitle = chapters[1].title,
                        progress = progress,
                        artworkAccent = null,
                        onArtworkLoaded = {},
                        onDismiss = {},
                        onToggleFavorite = {},
                        onToggleDebug = {},
                        onSeek = {},
                        onBookSeek = {},
                        onPreviousChapter = {},
                        onBack = {},
                        onPlayPause = onPlayPause,
                        onForward = {},
                        onNextChapter = {},
                        onUndoSeek = {},
                        onSpeed = onSpeed,
                        onTimer = {},
                        onBookmark = {},
                        onChapters = {}
                    )
                }
            }
        }
    }
}
