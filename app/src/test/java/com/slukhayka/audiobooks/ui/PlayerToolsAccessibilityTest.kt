package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.ui.components.SleepTimerSheet
import com.slukhayka.audiobooks.ui.components.SpeedSheet
import com.slukhayka.audiobooks.ui.screens.BookmarkBottomSheet
import com.slukhayka.audiobooks.ui.screens.BookmarksListSheet
import com.slukhayka.audiobooks.ui.screens.ChapterBottomSheet
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerToolsAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun speedSheetFocusesItsPaneAndKeepsPresetsAndExactSliderAdjustable() {
        var changedSpeed = 0f
        composeTestRule.setContent {
            PlayerTheme {
                SpeedSheet(
                    currentSpeed = 1.25f,
                    onSpeedChange = { changedSpeed = it },
                    onSaveForBook = {},
                    onSetDefault = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("speed_sheet")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Швидкість відтворення"))
        composeTestRule.onNodeWithTag("speed_sheet_heading")
            .assertIsFocused()
        composeTestRule.onNodeWithTag("speed_preset_1.25")
            .assertIsSelected()
            .assertContentDescriptionEquals("Швидкість 1,25 раза")
        composeTestRule.onNodeWithTag("exact_speed_slider")
            .assertContentDescriptionEquals("Точна швидкість відтворення")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(1.5f)
            }

        assertEquals(1.5f, changedSpeed, 0.001f)
    }

    @Test
    fun sleepTimerExposesOneRadioControlPerOptionAndSilentCountdown() {
        var selected: Int? = null
        var dismisses = 0
        composeTestRule.setContent {
            PlayerTheme {
                SleepTimerSheet(
                    currentTimerMinutes = 15,
                    remainingSeconds = 870,
                    onSelectTimer = { selected = it },
                    onExtendTimer = {},
                    onDismiss = { dismisses++ }
                )
            }
        }

        composeTestRule.onNodeWithTag("sleep_timer_sheet")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Таймер сну"))
        composeTestRule.onNodeWithTag("sleep_timer_heading")
            .assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "15 хвилин"))
        composeTestRule.onNodeWithTag("sleep_timer_options")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.SelectableGroup, Unit))
        composeTestRule.onNodeWithTag("sleep_timer_option_15")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
        composeTestRule.onNodeWithTag("sleep_timer_radio_15", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role).not())
        composeTestRule.onNodeWithTag("sleep_timer_countdown", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))

        assertEquals(15, selected)
        assertEquals(1, dismisses)
    }

    @Test
    fun sleepTimerHasAVisibleNonGestureExtensionAndExplicitClose() {
        var extensions = 0
        var dismisses = 0
        composeTestRule.setContent {
            PlayerTheme {
                SleepTimerSheet(
                    currentTimerMinutes = 5,
                    remainingSeconds = 240,
                    onSelectTimer = {},
                    onExtendTimer = { extensions++ },
                    onDismiss = { dismisses++ }
                )
            }
        }

        composeTestRule.onNodeWithTag("extend_sleep_timer_button")
            .assertContentDescriptionEquals("Додати 15 хвилин до таймера")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithContentDescription("Закрити таймер сну")
            .performClick()

        assertEquals(1, extensions)
        assertEquals(1, dismisses)
    }

    @Test
    fun sleepTimerDoesNotExposeAnUnavailableClippedExtensionAction() {
        composeTestRule.setContent {
            PlayerTheme {
                SleepTimerSheet(
                    currentTimerMinutes = 0,
                    remainingSeconds = 0,
                    onSelectTimer = {},
                    onExtendTimer = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("extend_sleep_timer_button")
            .assertDoesNotExist()
    }

    @Test
    fun chapterPickerNamesAndSelectsTheCurrentChapter() {
        val chapters = listOf(
            ChapterEntity("chapter-1", "book", 0, "Початок", 600),
            ChapterEntity("chapter-2", "book", 1, "Зустріч", 660)
        )
        var selected = -1
        composeTestRule.setContent {
            PlayerTheme {
                ChapterBottomSheet(
                    chapters = chapters,
                    selectedIndex = 1,
                    onSelect = { selected = it },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("chapter_sheet_heading").assertIsFocused()
        composeTestRule.onNodeWithTag("chapter_option_1")
            .assertIsSelected()
            .assertContentDescriptionEquals("Зустріч. Тривалість 11:00. Поточний розділ")
            .performClick()

        assertEquals(1, selected)
    }

    @Test
    fun chapterPickerNamesUnknownDurationWithoutAnnouncingZero() {
        val unknown = ChapterEntity("chapter-unknown", "book", 0, "Пролог", 0)
        composeTestRule.setContent {
            PlayerTheme {
                ChapterBottomSheet(
                    chapters = listOf(unknown),
                    selectedIndex = 0,
                    onSelect = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("chapter_option_0")
            .assertContentDescriptionEquals("Пролог. Тривалість невідома. Поточний розділ")
        composeTestRule.onAllNodesWithText("Тривалість невідома", useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule.onNodeWithTag("chapter_option_duration_0", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        composeTestRule.onAllNodesWithText("00:00", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun bookmarkSheetFocusesItsChapterAndPositionContextAndClosesExplicitly() {
        var dismisses = 0
        composeTestRule.setContent {
            PlayerTheme {
                BookmarkBottomSheet(
                    timestampSeconds = 320,
                    chapterTitle = "Зустріч",
                    onDismiss = { dismisses++ },
                    onSave = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("bookmark_sheet_heading")
            .assertIsFocused()
        composeTestRule.onNodeWithTag("bookmark_position_context")
            .assertContentDescriptionEquals("Закладка в розділі «Зустріч» на позиції 05:20")
        composeTestRule.onNodeWithContentDescription("Закрити додавання закладки")
            .performClick()

        assertEquals(1, dismisses)
    }

    @Test
    fun bookmarksListConfirmsDeletionAndRestoresAStableFocusTarget() {
        val bookmark = BookmarkEntity(
            id = 17,
            bookId = "work-1",
            editionId = "edition-1",
            chapterIndex = 1,
            chapterTitle = "Зустріч",
            timestampSeconds = 320,
            note = "Важливе місце",
            createdAt = 42
        )
        var deletedId: Long? = null
        composeTestRule.setContent {
            var bookmarks by remember { mutableStateOf(listOf(bookmark)) }
            PlayerTheme {
                BookmarksListSheet(
                    workTitle = "Трохи ненависті",
                    bookmarks = bookmarks,
                    onSelect = {},
                    onDelete = {
                        deletedId = it.id
                        bookmarks = bookmarks.filterNot { candidate -> candidate.id == it.id }
                    },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("bookmarks_sheet_heading").assertIsFocused()
        composeTestRule.onNodeWithTag("bookmarks_sheet_delete_17").performClick()
        composeTestRule.onNodeWithTag("bookmarks_sheet", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        composeTestRule.onNodeWithTag("book_detail_bookmark_delete_heading").assertIsFocused()
        composeTestRule.onAllNodesWithText(
            "Видалити закладку у «Трохи ненависті», розділ «Зустріч», 05:20?"
        ).assertCountEquals(1)

        composeTestRule.onNodeWithText("Скасувати").performClick()
        composeTestRule.onNodeWithTag("bookmarks_sheet_delete_17").assertIsFocused()
        composeTestRule.onNodeWithTag("bookmarks_sheet_delete_17").performClick()
        composeTestRule.onNodeWithTag("book_detail_bookmark_delete_confirm").performClick()

        composeTestRule.onNodeWithTag("bookmarks_sheet_heading").assertIsFocused()
        assertEquals(17L, deletedId)
    }

    @androidx.compose.runtime.Composable
    private fun PlayerTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
        AudiobookTheme(darkTheme = true) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                content()
            }
        }
    }
}
