package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.components.CompactBookCard
import com.slukhayka.audiobooks.ui.components.MiniPlayerBar
import com.slukhayka.audiobooks.ui.library.ListenComposer
import com.slukhayka.audiobooks.ui.screens.ListenBlockHeader
import com.slukhayka.audiobooks.ui.screens.ListenHeroCard
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
                CompactBookCard(
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
        compose.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun compactCardAlwaysAnnouncesWhenAConnectionIsRequired() {
        val streamingBook = book.copy(isDownloaded = false)
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                CompactBookCard(
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
                CompactBookCard(
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
    fun listenBlockTitleIsAHeadingAndMenuNamesItsBlock() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenBlockHeader(
                    title = "Щось коротке",
                    reason = "До години",
                    blockId = ListenComposer.BlockId.SHORT,
                    onMoveUp = {},
                    onMoveDown = {},
                    onHide = {}
                )
            }
        }

        compose.onNodeWithTag("listen_block_heading_SHORT")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithContentDescription("Дії блоку: Щось коротке")
            .assertHeightIsAtLeast(48.dp)
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
