package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.PosterCard
import com.slukhayka.audiobooks.ui.components.PosterDismissVisualSize
import com.slukhayka.audiobooks.ui.components.MiniPlayerBar
import com.slukhayka.audiobooks.ui.library.ListenComposer
import com.slukhayka.audiobooks.ui.screens.ListenHeroCard
import com.slukhayka.audiobooks.ui.screens.ListenShelvesManageEntry
import com.slukhayka.audiobooks.ui.screens.ListenShelvesSheetContent
import com.slukhayka.audiobooks.ui.screens.RecentlyListenedRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ListenAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = TestDataFactory.dataBooks().first()
    private val progress = TestDataFactory.seedPlaybackProgress(
        audiobooks = listOf(book),
        chapterIndex = 1,
        positionSeconds = 300L
    ).first()

    @Test
    fun compactCardIsOneWorkNodeWithASeparateContextualDismissAction() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                PosterCard(
                    book = book,
                    onClick = {},
                    onNotInterested = {},
                    progress = 0.5f
                )
            }
        }

        compose.onNodeWithTag("compact_book_${book.id}")
            .assertTextContains(book.title)
            .assertTextContains(book.displayAuthor)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Прослухано 50%. Доступно офлайн"
                )
            )
        compose.onNodeWithContentDescription("Не цікаво: ${book.title}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        // #372: the visible indicator shrank so it
        // no longer dominates the cover; the invisible touch target stays
        // at >= 48 dp.
        compose.onNodeWithTag("not_interested_visual_${book.id}", useUnmergedTree = true)
            .assertWidthIsEqualTo(PosterDismissVisualSize)
        compose.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun compactCardAlwaysAnnouncesWhenAConnectionIsRequired() {
        val streamingBook = book.copy(isDownloaded = false)
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                PosterCard(
                    book = streamingBook,
                    onClick = {},
                    progress = 0.5f
                )
            }
        }

        compose.onNodeWithTag("compact_book_${streamingBook.id}")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Прослухано 50%. Для прослуховування потрібне підключення до інтернету"
                )
            )
    }

    @Test
    fun compactCardTreatsALocalImportAsOfflineEvenWithoutDownloadedProjection() {
        val localImport = book.copy(
            id = "local-import",
            sourceUrl = "",
            isDownloaded = false
        )
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                PosterCard(
                    book = localImport,
                    onClick = {}
                )
            }
        }

        compose.onNodeWithTag("compact_book_${localImport.id}")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Доступно офлайн"
                )
            )
    }

    @Test
    fun listenBlockTitleIsAHeading() {
        // v1.4 E1: the block header IS the canonical section header — the ⋮
        // menu is gone, and the a11y heading contract rides on the title
        // text itself (uppercased SECTION level), so TalkBack navigation by
        // headings survives the migration.
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                AppSectionHeader(
                    title = "Щось коротке",
                    subtitle = "До години",
                    modifier = Modifier.testTag("listen_block_heading_SHORT")
                )
            }
        }

        compose.onNodeWithTag("listen_block_heading_SHORT").assertExists()
        compose.onNodeWithText("ЩОСЬ КОРОТКЕ")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun manageShelvesSheetReordersHidesAndRestores() {
        val blocks = listOf(
            ListenComposer.Block(ListenComposer.BlockId.ALMOST_DONE, "Майже дочитали", "До кінця 12 хв", emptyList()),
            ListenComposer.Block(ListenComposer.BlockId.SHORT, "Щось коротке", "~1 год прослуховування", emptyList())
        )
        var hidden by mutableStateOf(setOf<ListenComposer.BlockId>())
        var sheetOpen by mutableStateOf(false)
        var order by mutableStateOf(blocks.map { it.id })

        fun reorder(id: ListenComposer.BlockId, delta: Int) {
            val list = order.toMutableList()
            val from = list.indexOf(id)
            val to = from + delta
            if (from < 0 || to < 0 || to >= list.size) return
            list.removeAt(from)
            list.add(to, id)
            order = list
        }

        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Column {
                    ListenShelvesManageEntry(onClick = { sheetOpen = true })
                    if (sheetOpen) {
                        ListenShelvesSheetContent(
                            blocks = order.mapNotNull { id -> blocks.find { it.id == id } },
                            hiddenIds = hidden,
                            onMoveUp = { reorder(it, -1) },
                            onMoveDown = { reorder(it, +1) },
                            onHide = { hidden = hidden + it },
                            onUnhide = { hidden = hidden - it },
                            onRestoreAll = { hidden = emptySet() },
                            onClose = { sheetOpen = false }
                        )
                    }
                }
            }
        }

        // The one door opens the sheet and lands focus on its heading.
        compose.onNodeWithTag("listen_manage_shelves").performClick()
        compose.onNodeWithTag("listen_shelves_sheet_heading").assertIsFocused()
        // Every row control keeps the 48 dp touch target.
        compose.onNodeWithTag("listen_shelf_up_SHORT").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("listen_shelf_down_SHORT").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("listen_shelf_toggle_SHORT").assertHeightIsAtLeast(48.dp)
        // ↑↓ reorder in the sheet.
        compose.onNodeWithTag("listen_shelf_down_ALMOST_DONE").performClick()
        assertEquals(listOf(ListenComposer.BlockId.SHORT, ListenComposer.BlockId.ALMOST_DONE), order)
        compose.onNodeWithTag("listen_shelf_up_ALMOST_DONE").performClick()
        assertEquals(listOf(ListenComposer.BlockId.ALMOST_DONE, ListenComposer.BlockId.SHORT), order)
        // Hide marks the row «Приховано» and raises restore-all; the same
        // toggle restores a single shelf.
        compose.onNodeWithTag("listen_shelf_toggle_SHORT").performClick()
        assertTrue(ListenComposer.BlockId.SHORT in hidden)
        compose.onNodeWithText("Приховано").assertExists()
        compose.onNodeWithTag("listen_shelves_restore_all").assertExists()
        compose.onNodeWithTag("listen_shelf_toggle_SHORT").performClick()
        assertFalse(ListenComposer.BlockId.SHORT in hidden)
        compose.onNodeWithText("Приховано").assertDoesNotExist()
    }

    @Test
    fun heroActionsNameTheirWorkAndKeepFullTouchTargets() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Box {
                    ListenHeroCard(
                        book = book,
                        progress = progress,
                        cumulativePositionSeconds = 900L,
                        onResumeClick = {},
                        onBookClick = {}
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Продовжити слухати: ${book.title}")
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Відкрити книгу: ${book.title}")
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("ПРОДОВЖИТИ СЛУХАТИ")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun recentCardIsOneWorkNodeWithAContextualPlayAction() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                RecentlyListenedRow(
                    book = book,
                    progress = progress,
                    cumulativePositionSeconds = 900L,
                    onClick = {},
                    onPlayClick = {}
                )
            }
        }

        compose.onNodeWithTag("recently_listened_${book.id}")
            .assertTextContains(book.title)
        compose.onNodeWithContentDescription("Відтворити: ${book.title}")
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun miniPlayerHasASeparateSummaryAndContextualControls() {
        val chapters = TestDataFactory.dataChapters(listOf(book))
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                MiniPlayerBar(
                    playerState = PlayerState(
                        currentBook = book,
                        chapters = chapters,
                        currentChapterIndex = 1,
                        isPlaying = true,
                        isOfflineMode = true
                    ),
                    onPlayPauseClick = {},
                    onSkipNextClick = {},
                    onBarClick = {}
                )
            }
        }

        compose.onNodeWithTag("mini_player_summary")
            .assertTextContains(book.title)
            .assertTextContains(chapters[1].title)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Відтворюється. Доступно офлайн"
                )
            )
        compose.onNodeWithContentDescription("Пауза: ${book.title}")
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Наступний розділ: ${book.title}")
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun viewingAnotherBookKeepsMiniPlayerControlsBoundToItsCurrentBook() {
        var toggles = 0
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                MiniPlayerBar(
                    playerState = PlayerState(currentBook = book),
                    viewedBookId = "another-book",
                    onPlayPauseClick = { toggles++ }, onSkipNextClick = {}, onBarClick = {}
                )
            }
        }
        compose.onNodeWithTag("mini_player_summary").assertTextContains(book.title)
            .assertTextContains("У плеєрі", substring = true)
        compose.onNodeWithContentDescription("Відтворити: ${book.title}").performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun heroCriticalActionsRemainReachableAtTwoHundredPercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Box(
                        modifier = Modifier
                            .width(360.dp)
                            .height(420.dp)
                    ) {
                        ListenHeroCard(
                            book = book,
                            progress = progress,
                            cumulativePositionSeconds = 900L,
                            onResumeClick = {},
                            onBookClick = {}
                        )
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Продовжити слухати: ${book.title}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Відкрити книгу: ${book.title}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }
}
