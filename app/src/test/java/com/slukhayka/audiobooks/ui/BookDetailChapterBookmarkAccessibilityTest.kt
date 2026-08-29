package com.slukhayka.audiobooks.ui

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.getOrNull
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.ui.components.BookmarkDialog
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.screens.BookmarkDeleteConfirmation
import com.slukhayka.audiobooks.ui.screens.BookmarkRowItem
import com.slukhayka.audiobooks.ui.screens.BookDeleteModalLifecycle
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
    fun chapterCanReceiveAccessibilityFocusBeforeItIsActivated() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ChapterRowItem(
                    chapter = chapter,
                    index = 3,
                    isCurrent = false,
                    isPlaying = false,
                    onPlayClick = {},
                    onPauseClick = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("book_detail_chapter_${chapter.id}")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
    }

    @Test
    fun suppliedChapterRequesterFocusesTheExposedAccessibilityNode() {
        lateinit var chapterRequester: FocusRequester
        composeTestRule.setContent {
            val requester = remember { FocusRequester() }
            chapterRequester = requester
            AudiobookTheme(darkTheme = true) {
                ChapterRowItem(
                    chapter = chapter,
                    index = 3,
                    isCurrent = false,
                    isPlaying = false,
                    focusRequester = requester,
                    onPlayClick = {},
                    onPauseClick = {}
                )
            }
        }

        composeTestRule.runOnIdle {
            assertTrue(chapterRequester.requestFocus())
        }
        composeTestRule.onNodeWithTag("book_detail_chapter_${chapter.id}")
            .assertIsFocused()
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
                var open by remember { mutableStateOf(false) }
                val origin = remember { FocusRequester() }
                RestoreFocusAfterModal(open, origin)
                Box {
                    Button(
                        onClick = { open = true },
                        modifier = Modifier
                            .focusRequester(origin)
                            .testTag("bookmark_delete_origin")
                            .accessibilityModalBackground(open)
                    ) { Text("Відкрити видалення") }
                    if (open) {
                        BookmarkDeleteConfirmation(
                            workTitle = "Трохи ненависті",
                            bookmark = bookmark,
                            onConfirm = {
                                confirmed = true
                                open = false
                            },
                            onDismiss = { open = false }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("bookmark_delete_origin").performClick()
        composeTestRule.onNodeWithTag("bookmark_delete_origin", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        composeTestRule.onNodeWithTag("book_detail_bookmark_delete_heading")
            .assertIsFocused()
        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithText(
            "Видалити закладку у «Трохи ненависті», розділ «${chapter.title}», 02:05?"
        ).assertExists()
        composeTestRule.onNodeWithText("Скасувати").performClick()
        composeTestRule.onNodeWithTag("bookmark_delete_origin").assertIsFocused().performClick()
        composeTestRule.onNodeWithTag("book_detail_bookmark_delete_confirm").performClick()
        composeTestRule.onNodeWithTag("bookmark_delete_origin").assertIsFocused()
        assertTrue(confirmed)
    }

    @Test
    fun destructiveBookModalLifecycleKeepsContextAndReturnsToExactTriggerAtTwoHundredPercent() {
        var removed = false
        var confirmed = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    var showOptions by remember { mutableStateOf(false) }
                    var showConfirmation by remember { mutableStateOf(false) }
                    val origin = remember { FocusRequester() }
                    Box(modifier = Modifier.width(320.dp).height(480.dp)) {
                        Button(
                            onClick = { showOptions = true },
                            modifier = Modifier
                                .focusRequester(origin)
                                .testTag("book_delete_origin")
                                .accessibilityModalBackground(showOptions || showConfirmation)
                        ) {
                            Text("Видалити Трохи ненависті")
                        }
                        BookDeleteModalLifecycle(
                            workTitle = "Трохи ненависті",
                            isDownloaded = true,
                            showOptions = showOptions,
                            showConfirmation = showConfirmation,
                            returnFocusRequester = origin,
                            onRemoveFromLibrary = { removed = true },
                            onDeleteDownloadedCopy = {},
                            onConfirmDelete = { confirmed = true },
                            onOptionsDismiss = { showOptions = false },
                            onRequestConfirmation = { showConfirmation = true },
                            onConfirmationDismiss = { showConfirmation = false }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("book_delete_origin").performClick()
        composeTestRule.onNodeWithTag("book_delete_origin", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        composeTestRule.onNodeWithTag("book_detail_delete_options_heading")
            .assertIsFocused()
        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithTag("delete_remove_from_library")
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains("Прибрати «Трохи ненависті» з медіатеки")
            .assertTextContains("Книга зникне зі списку, файли на пристрої лишаться")
            .performClick()
        composeTestRule.onNodeWithTag("book_delete_origin").assertIsFocused()
        assertTrue(removed)

        composeTestRule.onNodeWithTag("book_delete_origin").performClick()
        composeTestRule.onNodeWithTag("delete_book_and_files")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("book_detail_delete_confirm_heading")
            .assertIsFocused()
        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithText(
            "Буде видалено «Трохи ненависті» разом із розділами, закладками, прогресом і завантаженими файлами. Дію не можна скасувати."
        ).assertExists()
        composeTestRule.onNodeWithText("Скасувати").performClick()
        composeTestRule.onNodeWithTag("book_delete_origin").assertIsFocused()

        composeTestRule.onNodeWithTag("book_delete_origin").performClick()
        composeTestRule.onNodeWithTag("delete_book_and_files").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("book_detail_delete_confirm")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onNodeWithTag("book_delete_origin").assertIsFocused()
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
