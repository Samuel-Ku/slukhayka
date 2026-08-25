package com.slukhayka.audiobooks.ui

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.getOrNull
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.ui.components.BookmarkDialog
import com.slukhayka.audiobooks.ui.screens.BookmarkDeleteConfirmation
import com.slukhayka.audiobooks.ui.screens.BookmarkRowItem
import com.slukhayka.audiobooks.ui.screens.ChapterRowItem
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BookDetailChapterBookmarkAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val chapter = ChapterEntity(
        id = "chapter-4",
        bookId = "work-1",
        chapterIndex = 3,
        title = "Крихкий мир",
        durationSeconds = 305
    )

    private val bookmark = BookmarkEntity(
        id = 42,
        bookId = "work-1",
        chapterIndex = 3,
        chapterTitle = chapter.title,
        timestampSeconds = 125,
        note = "Важлива думка"
    )

    @Test
    fun playingCurrentChapterIsOneContextualPauseAction() {
        var played = false
        var paused = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    ChapterRowItem(
                        chapter = chapter,
                        index = 3,
                        isCurrent = true,
                        isPlaying = true,
                        onPlayClick = { played = true },
                        onPauseClick = { paused = true }
                    )
                }
            }
        }

        composeTestRule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule.onNodeWithTag("book_detail_chapter_${chapter.id}")
            .assertContentDescriptionEquals("Розділ: ${chapter.title}, тривалість 05:05")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Поточний розділ, відтворюється"
                )
            )
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(
                SemanticsMatcher("pause action names the Chapter") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label ==
                        "Пауза: ${chapter.title}"
                }
            )
            .performClick()

        assertTrue(paused)
        assertFalse(played)
    }

    @Test
    fun currentPausedChapterKeepsCurrentStateButOffersResume() {
        var played = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ChapterRowItem(
                    chapter = chapter,
                    index = 3,
                    isCurrent = true,
                    isPlaying = false,
                    onPlayClick = { played = true },
                    onPauseClick = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("book_detail_chapter_${chapter.id}")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Поточний розділ, на паузі"
                )
            )
            .assert(
                SemanticsMatcher("resume action names the Chapter") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label ==
                        "Продовжити: ${chapter.title}"
                }
            )
            .performClick()

        assertTrue(played)
    }

    @Test
    fun bookmarkActionsNameWorkChapterAndPosition() {
        var jumped = false
        var deleted = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookmarkRowItem(
                    bookmark = bookmark,
                    workTitle = "Трохи ненависті",
                    onJumpClick = { jumped = true },
                    onDeleteClick = { deleted = true }
                )
            }
        }

        val jumpLabel =
            "Перейти до закладки у «Трохи ненависті», розділ «${chapter.title}», 02:05"
        val deleteLabel =
            "Видалити закладку у «Трохи ненависті», розділ «${chapter.title}», 02:05"
        composeTestRule.onNodeWithContentDescription(jumpLabel)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onNodeWithContentDescription(deleteLabel)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertTrue(jumped)
        assertTrue(deleted)
    }

    @Test
    fun bookmarkDialogAnnouncesPaneFocusesInputAndReturnsNote() {
        var savedNote: String? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookmarkDialog(
                    timestampSeconds = bookmark.timestampSeconds,
                    chapterTitle = bookmark.chapterTitle,
                    onDismiss = {},
                    onSave = { savedNote = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("bookmark_dialog", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Додати закладку"
                )
            )
        composeTestRule.onNodeWithText("Додати закладку")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithTag("bookmark_note_input")
            .assertIsFocused()
            .performTextInput("Нова нотатка")
        composeTestRule.onNodeWithTag("save_bookmark_button").performClick()

        assertEquals("Нова нотатка", savedNote)
    }

    @Test
    fun destructiveBookmarkConfirmationNamesExactBookmark() {
        var confirmed = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookmarkDeleteConfirmation(
                    workTitle = "Трохи ненависті",
                    bookmark = bookmark,
                    onConfirm = { confirmed = true },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithText(
            "Видалити закладку у «Трохи ненависті», розділ «${chapter.title}», 02:05?"
        ).assertExists()
        composeTestRule.onNodeWithText("Видалити").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun chapterAndBookmarkControlsRemainReachableAtTwoHundredPercentFontScale() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    Surface {
                        Column(modifier = Modifier.width(320.dp)) {
                            ChapterRowItem(
                                chapter = chapter,
                                index = 3,
                                isCurrent = false,
                                isPlaying = false,
                                onPlayClick = {},
                                onPauseClick = {}
                            )
                            BookmarkRowItem(
                                bookmark = bookmark,
                                workTitle = "Трохи ненависті",
                                onJumpClick = {},
                                onDeleteClick = {}
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("book_detail_chapter_${chapter.id}")
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription(
            "Перейти до закладки у «Трохи ненависті», розділ «${chapter.title}», 02:05"
        ).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription(
            "Видалити закладку у «Трохи ненависті», розділ «${chapter.title}», 02:05"
        ).assertHeightIsAtLeast(48.dp)
    }
}
