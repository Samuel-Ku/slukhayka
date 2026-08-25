package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.screens.PlayerProgressUi
import com.slukhayka.audiobooks.ui.screens.PlayerScreenContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = AudiobookEntity(
        id = "accessible-player",
        title = "Нейромант",
        author = "Вільям Гібсон",
        narrator = "Олександр Завальський",
        description = "",
        coverDrawableRes = 0,
        genre = "Кіберпанк",
        sourceUrl = "https://example.invalid/player",
        totalDurationSeconds = 1_980,
        totalChapters = 3
    )
    private val chapters = listOf(600L, 660L, 720L).mapIndexed { index, duration ->
        ChapterEntity(
            id = "accessible-chapter-$index",
            bookId = book.id,
            chapterIndex = index,
            title = "Розділ ${index + 1}",
            durationSeconds = duration
        )
    }
    private val state = PlayerState(
        currentBook = book,
        chapters = chapters,
        currentChapterIndex = 1,
        isPlaying = true,
        currentPositionMs = 320_000,
        durationMs = 660_000
    )
    private val progress = PlayerProgressUi(
        chapterFraction = 320f / 660f,
        bookFraction = 920f / 1_980f,
        bookPositionSeconds = 920,
        bookDurationSeconds = 1_980,
        chapterMarkers = listOf(600f / 1_980f, 1_260f / 1_980f),
        bookmarkMarkers = listOf(800f / 1_980f)
    )

    @Test
    fun playerStartsOnOneMeaningfulWorkEditionAndChapterContext() {
        setPlayerContent()

        composeTestRule.onNodeWithTag("player_context")
            .assertIsFocused()
            .assertContentDescriptionEquals(
                "Нейромант. Автор: Вільям Гібсон. Начитка: Олександр Завальський. Поточний розділ: Розділ 2"
            )
        composeTestRule.onNodeWithContentDescription("Обкладинка: Нейромант", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun transportControlsExposeUkrainianContextAndPlaybackState() {
        setPlayerContent()

        composeTestRule.onNodeWithTag("player_play_pause_button")
            .assertContentDescriptionEquals("Поставити на паузу. Нейромант, Розділ 2")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Відтворюється"
                )
            )
        composeTestRule.onNodeWithContentDescription("Назад на 15 секунд. Нейромант, Розділ 2")
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Наступний розділ після «Розділ 2». Нейромант")
            .assertIsDisplayed()
    }

    @Test
    fun bookAndChapterSlidersStayDistinctHonestAndAdjustable() {
        setPlayerContent()

        composeTestRule.onNodeWithTag("player_progress_slider")
            .assertContentDescriptionEquals("Позиція в поточному розділі")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "05:20 із 11:00"
                )
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        composeTestRule.onNodeWithTag("book_progress_slider")
            .assertContentDescriptionEquals("Позиція у книзі")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "15:20 із 33:00"
                )
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        composeTestRule.onNodeWithTag("book_progress_markers", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.HideFromAccessibility,
                    Unit
                )
            )
        composeTestRule.onNodeWithTag("player_progress_slider")
            .assert(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion).not()
            )
        composeTestRule.onNodeWithTag("book_progress_slider")
            .assert(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion).not()
            )
    }

    @Test
    fun playbackErrorOffersOnePoliteRetry() {
        var retryClicks = 0
        setPlayerContent(
            playerState = state.copy(lastErrorMsg = "Книга недоступна"),
            onRetryPlayback = { retryClicks++ }
        )

        composeTestRule.onNodeWithTag("player_playback_error")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite
                )
            )
        composeTestRule.onNodeWithContentDescription("Повторити відтворення Нейромант")
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, retryClicks)
    }

    @Test
    fun twoHundredPercentFontKeepsEveryPrimaryControlReachable() {
        val density = composeTestRule.density.density
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) {
                            PlayerContent()
                        }
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("player_play_pause_button")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("speed_chip")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("sleep_timer_chip")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("add_bookmark_chip")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("chapters_chip")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    private fun setPlayerContent(
        playerState: PlayerState = state,
        onRetryPlayback: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PlayerContent(playerState, onRetryPlayback)
                }
            }
        }
    }

    @Composable
    private fun PlayerContent(
        playerState: PlayerState = state,
        onRetryPlayback: () -> Unit = {}
    ) {
        PlayerScreenContent(
            playerState = playerState,
            book = book,
            currentChapterTitle = chapters[1].title,
            progress = progress,
            artworkAccent = Color(0xFF355D67),
            onArtworkLoaded = {},
            onDismiss = {},
            onToggleFavorite = {},
            onToggleDebug = {},
            onSeek = {},
            onBookSeek = {},
            onPreviousChapter = {},
            onBack = {},
            onPlayPause = {},
            onForward = {},
            onNextChapter = {},
            onUndoSeek = {},
            onSpeed = {},
            onTimer = {},
            onBookmark = {},
            onChapters = {},
            onRetryPlayback = onRetryPlayback
        )
    }
}
