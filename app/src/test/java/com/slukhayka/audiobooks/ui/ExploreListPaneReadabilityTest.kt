package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.AdaptiveNavigationLayout
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.adaptive.WindowLayout
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.NarrowBookRowMaxWidth
import com.slukhayka.audiobooks.ui.components.WideDetailPane
import com.slukhayka.audiobooks.ui.screens.BookListScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1208 / #1235 — the Explore book lists as a READABLE list-detail pane.
 *
 * The policy tests elsewhere pin the predicate. This class pins the real
 * containers the decision names:
 *  - 600 dp with the 80 dp rail hands the list ≈208 dp;
 *  - 840 dp with the 360 dp mini-player column hands it ≈192 dp;
 * and it asserts, on those panes, that the canonical [BookRow] and the person
 * header still read: the title keeps ≈100–176 dp instead of the strip a rank,
 * a 64 dp cover and a 48 dp play target used to leave, at normal AND 200 %
 * font scale, while TalkBack still sees one row and one heading.
 *
 * Both widths are BELOW [NarrowBookRowMaxWidth], so the row's compact layout is
 * the thing under test here; a full-width list is pinned by the existing suites.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w600dp-h1000dp-420dpi", sdk = [36])
class ExploreListPaneReadabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book: AudiobookEntity = TestDataFactory.dataBooks().first().copy(
        id = "book-narrow",
        title = "Тіні забутих предків. Повне видання з коментарями",
        author = "Михайло Коцюбинський",
        genre = "",
        isDownloaded = true,
        totalChapters = 4,
        totalDurationSeconds = 1_800
    )

    private val longPersonName = "Олександр Довженко-Петренко"

    @Test
    fun sixHundredDpGivesTheListAbout208DpAndKeepsTheRowAndPersonHeaderReadable() {
        showBookList(miniPlayer = false)

        assertPaneWidth(208f)

        val heading = composeTestRule.onNodeWithTag("secondary_screen_heading")
            .assertIsDisplayed()
            .assertTextEquals(longPersonName)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the person heading must keep the pane's width, was ${heading.width()} dp",
            heading.width() >= 150f
        )

        val title = titleBounds()
        assertTrue(
            "the BookRow title must get the text column (>=100 dp), was ${title.width()} dp " +
                "in a ${paneWidth()} dp pane",
            title.width() >= 100f
        )

        // The play action is still a separate 48 dp target inside the same row.
        composeTestRule.onNodeWithContentDescription("Відтворити: ${book.title}")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun eightHundredFortyDpWithTheMiniPlayerColumnGivesTheListAbout192DpAndStaysReadable() {
        showBookList(miniPlayer = true)

        assertPaneWidth(192f)

        val heading = composeTestRule.onNodeWithTag("secondary_screen_heading")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the person heading must survive the 192 dp pane, was ${heading.width()} dp",
            heading.width() >= 140f
        )

        val title = titleBounds()
        assertTrue(
            "the BookRow title must survive the mini-player's 192 dp pane " +
                "(>=90 dp), was ${title.width()} dp",
            title.width() >= 90f
        )
    }

    @Test
    fun theRankAndThePlayActionMoveBelowTheTitleInsteadOfSqueezingIt() {
        composeTestRule.setContent {
            Harness(miniPlayer = false) {
                BookRow(
                    title = book.title,
                    book = book,
                    leading = {
                        Box(modifier = Modifier.testTag("rank_badge")) {
                            Text("1")
                        }
                    },
                    trailing = {
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("play_target")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "play"
                            )
                        }
                    },
                    onClick = {},
                    testTag = "rank_row"
                )
            }
        }

        val title = titleBounds("rank_row_title")
        val rank = composeTestRule.onNodeWithTag("rank_badge", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the title must not be squeezed by the rank: title width=${title.width()} dp",
            title.width() >= 100f
        )
        assertTrue(
            "the rank belongs on the row's second line: rank.top=${rank.top}, " +
                "title.bottom=${title.bottom}",
            rank.top.value >= title.bottom.value - 1f
        )
        composeTestRule.onNodeWithTag("play_target", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun twoHundredPercentFontScaleKeepsTheSameTitlesDisplayedAndFocusableByTalkBack() {
        showBookList(miniPlayer = false, fontScale = 2f)

        val heading = composeTestRule.onNodeWithTag("secondary_screen_heading")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(
            "at 200 % the person heading still needs the pane width, was ${heading.width()} dp",
            heading.width() >= 150f
        )

        val title = titleBounds()
        assertTrue(
            "at 200 % the BookRow title must still be a real text column " +
                "(>=90 dp), was ${title.width()} dp",
            title.width() >= 90f
        )
        assertTrue(
            "at 200 % the title must take at least one full line, was ${title.height()} dp",
            title.height() >= 24f
        )

        // TalkBack: the heading is a heading; the row is ONE clickable node
        // with the availability state, not a pile of unlabelled children.
        composeTestRule.onNodeWithTag("secondary_screen_heading")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithTag("book_item_${book.id}")
            .assertHasClickAction()
            .assertTextContains(book.title)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
    }

    // ------------------------------------------------------------------ setup

    private fun showBookList(miniPlayer: Boolean, fontScale: Float = 1f) {
        composeTestRule.setContent {
            Harness(miniPlayer = miniPlayer, fontScale = fontScale) {
                BookListScreen(
                    title = longPersonName,
                    countLabel = "1 твір",
                    emptyMessage = "Порожньо",
                    isLoading = false,
                    books = listOf(book),
                    onBackClick = {},
                    onBookClick = {},
                    onPlayClick = {},
                    testTag = "explore_list",
                    headerAction = {
                        IconButton(onClick = {}, modifier = Modifier.testTag("header_action")) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "bookmark"
                            )
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun Harness(
        miniPlayer: Boolean,
        fontScale: Float = 1f,
        list: @Composable () -> Unit
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale = fontScale)
        ) {
            AudiobookTheme(darkTheme = true) {
                AdaptiveNavigationLayout(
                    layout = WindowLayout.EXPANDED,
                    selectedTab = SelectedTab.EXPLORE,
                    bookDetailOpen = true,
                    onSelect = {},
                    miniPlayer = if (miniPlayer) {
                        { Box(modifier = Modifier.fillMaxSize()) }
                    } else {
                        null
                    }
                ) {
                    WideDetailPane(
                        list = list,
                        detail = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("detail_placeholder")
                            )
                        }
                    )
                }
            }
        }
    }

    private fun paneWidth(): Float {
        val pane = composeTestRule.onNodeWithTag("wide_detail_list")
            .getUnclippedBoundsInRoot()
        return pane.right.value - pane.left.value
    }

    private fun assertPaneWidth(expected: Float) {
        assertEquals(
            "the real list column must be the width the decision names",
            expected,
            paneWidth(),
            3f
        )
    }

    private fun titleBounds(tag: String = "book_item_${book.id}_title") = composeTestRule
        .onNodeWithTag(tag, useUnmergedTree = true)
        .assertIsDisplayed()
        .getUnclippedBoundsInRoot()

    private fun androidx.compose.ui.unit.DpRect.width(): Float = right.value - left.value
    private fun androidx.compose.ui.unit.DpRect.height(): Float = bottom.value - top.value
}
