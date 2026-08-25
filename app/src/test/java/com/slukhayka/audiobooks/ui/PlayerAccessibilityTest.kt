package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.screens.PlayerProgressUi
import com.slukhayka.audiobooks.ui.screens.PlayerQuickTool
import com.slukhayka.audiobooks.ui.screens.PlayerScreenContent
import com.slukhayka.audiobooks.ui.screens.BookmarkBottomSheet
import com.slukhayka.audiobooks.ui.screens.ChapterBottomSheet
import com.slukhayka.audiobooks.ui.components.SleepTimerSheet
import com.slukhayka.audiobooks.ui.components.SpeedSheet
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
        var chapterSeek = -1f
        var bookSeek = -1f
        setPlayerContent(
            onSeek = { chapterSeek = it },
            onBookSeek = { bookSeek = it }
        )

        composeTestRule.onNodeWithTag("player_progress_slider")
            .assertContentDescriptionEquals("Позиція в поточному розділі")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "05:20 із 11:00"
                )
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        composeTestRule.onNodeWithTag("book_progress_slider")
            .assertContentDescriptionEquals("Позиція у книзі")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "15:20 із 33:00"
                )
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        composeTestRule.onNodeWithTag("book_progress_markers", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.HideFromAccessibility,
                    Unit
                )
            )
        composeTestRule.onNodeWithTag("chapter_progress_visual_row", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        composeTestRule.onNodeWithTag("book_progress_visual_row", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        composeTestRule.onNodeWithTag("player_progress_slider")
            .assert(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion).not()
            )
        composeTestRule.onNodeWithTag("book_progress_slider")
            .assert(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion).not()
            )

        assertEquals(0.25f, chapterSeek, 0.001f)
        assertEquals(0.75f, bookSeek, 0.001f)
    }

    @Test
    fun unknownPlayerDurationsStayLocalizedAndNeverRenderZeroAsDuration() {
        val unknownProgress = progress.copy(
            chapterFraction = 0f,
            bookFraction = 0f,
            bookPositionSeconds = 0,
            bookDurationSeconds = 0
        )
        setPlayerContent(
            playerState = state.copy(currentPositionMs = 0, durationMs = 0),
            progressUi = unknownProgress
        )

        composeTestRule.onAllNodesWithText("Тривалість невідома", useUnmergedTree = true)
            .assertCountEquals(2)
        composeTestRule.onAllNodesWithText("00:00  /  00:00", useUnmergedTree = true)
            .assertCountEquals(0)
        composeTestRule.onNodeWithTag("player_progress_slider")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Тривалість невідома"
                )
            )
        composeTestRule.onNodeWithTag("book_progress_slider")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Тривалість невідома"
                )
            )
    }

    @Test
    fun quickToolOwnerHidesPlayerAndRestoresEachExactTriggerAtTwoHundredPercent() {
        val density = composeTestRule.density.density
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                AudiobookTheme(darkTheme = true) {
                    var activeTool by remember { mutableStateOf<PlayerQuickTool?>(null) }
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(320.dp).height(480.dp)) {
                            PlayerContent(
                                activeTool = activeTool,
                                onSpeed = { activeTool = PlayerQuickTool.Speed },
                                onTimer = { activeTool = PlayerQuickTool.Timer },
                                onBookmark = { activeTool = PlayerQuickTool.Bookmark },
                                onChapters = { activeTool = PlayerQuickTool.Chapters }
                            )
                            when (activeTool) {
                                PlayerQuickTool.Speed -> SpeedSheet(
                                    currentSpeed = 1.25f,
                                    onSpeedChange = {},
                                    onSaveForBook = { activeTool = null },
                                    onSetDefault = { activeTool = null },
                                    onDismiss = { activeTool = null }
                                )
                                PlayerQuickTool.Timer -> SleepTimerSheet(
                                    currentTimerMinutes = 15,
                                    onSelectTimer = {},
                                    onDismiss = { activeTool = null }
                                )
                                PlayerQuickTool.Bookmark -> BookmarkBottomSheet(
                                    timestampSeconds = 320,
                                    chapterTitle = chapters[1].title,
                                    onDismiss = { activeTool = null },
                                    onSave = { activeTool = null }
                                )
                                PlayerQuickTool.Chapters -> ChapterBottomSheet(
                                    chapters = chapters,
                                    selectedIndex = 1,
                                    onSelect = { activeTool = null },
                                    onDismiss = { activeTool = null }
                                )
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("speed_chip")
            .assertContentDescriptionEquals("Швидкість: 1.0×")
        composeTestRule.onNodeWithTag("speed_chip_value", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        composeTestRule.onNodeWithTag("speed_chip_label", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))

        listOf(
            ToolExpectation(
                triggerTag = "speed_chip",
                headingTag = "speed_sheet_heading",
                closeDescription = "Закрити налаштування швидкості"
            ),
            ToolExpectation(
                triggerTag = "sleep_timer_chip",
                headingTag = "sleep_timer_heading",
                closeDescription = "Закрити таймер сну"
            ),
            ToolExpectation(
                triggerTag = "add_bookmark_chip",
                headingTag = "bookmark_sheet_heading",
                closeDescription = "Закрити додавання закладки"
            ),
            ToolExpectation(
                triggerTag = "chapters_chip",
                headingTag = "chapter_sheet_heading",
                closeDescription = "Закрити вибір розділу"
            )
        ).forEach { tool ->
            composeTestRule.onNodeWithTag(tool.triggerTag)
                .performScrollTo()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeTestRule.onNodeWithTag(tool.headingTag)
                .assertIsFocused()
            composeTestRule.onNodeWithTag("full_player_screen", useUnmergedTree = true)
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.HideFromAccessibility,
                        Unit
                    )
                )
            composeTestRule.onNodeWithContentDescription(tool.closeDescription)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(tool.triggerTag)
                .assertIsFocused()
        }

        composeTestRule.onNodeWithTag("sleep_timer_chip")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("sleep_timer_option_30")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sleep_timer_chip")
            .assertIsFocused()
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
        onRetryPlayback: () -> Unit = {},
        progressUi: PlayerProgressUi = progress,
        onSeek: (Float) -> Unit = {},
        onBookSeek: (Float) -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PlayerContent(
                        playerState,
                        onRetryPlayback,
                        progressUi = progressUi,
                        onSeek = onSeek,
                        onBookSeek = onBookSeek
                    )
                }
            }
        }
    }

    @Composable
    private fun PlayerContent(
        playerState: PlayerState = state,
        onRetryPlayback: () -> Unit = {},
        activeTool: PlayerQuickTool? = null,
        progressUi: PlayerProgressUi = progress,
        onSeek: (Float) -> Unit = {},
        onBookSeek: (Float) -> Unit = {},
        onSpeed: () -> Unit = {},
        onTimer: () -> Unit = {},
        onBookmark: () -> Unit = {},
        onChapters: () -> Unit = {}
    ) {
        PlayerScreenContent(
            playerState = playerState,
            book = book,
            currentChapterTitle = chapters[1].title,
            progress = progressUi,
            artworkAccent = Color(0xFF355D67),
            onArtworkLoaded = {},
            onDismiss = {},
            onToggleFavorite = {},
            onToggleDebug = {},
            onSeek = onSeek,
            onBookSeek = onBookSeek,
            onPreviousChapter = {},
            onBack = {},
            onPlayPause = {},
            onForward = {},
            onNextChapter = {},
            onUndoSeek = {},
            onSpeed = onSpeed,
            onTimer = onTimer,
            onBookmark = onBookmark,
            onChapters = onChapters,
            onRetryPlayback = onRetryPlayback,
            activeTool = activeTool
        )
    }

    private data class ToolExpectation(
        val triggerTag: String,
        val headingTag: String,
        val closeDescription: String
    )
}
