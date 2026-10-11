package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.components.PosterWidth
import com.slukhayka.audiobooks.ui.components.WideDetailPane
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.library.libraryGridEntries
import com.slukhayka.audiobooks.ui.screens.LibraryBookGrid
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1206 — the Медіатека's book grid in the 600–839 dp band, as GEOMETRY.
 *
 * The defect the ticket files: a wide window at exactly its 600 dp breakpoint
 * hands the Library's list pane 0.4 of its width — 239.6 dp — and the grid has
 * to live inside that. With a FIXED two-column grid each tile was forced to
 * (239.6 − 44) / 2 = 97.8 dp, i.e. 22 dp UNDER the canonical poster; nothing in
 * the golden set rendered that band (the fixtures are 411 dp and 840 dp), so the
 * squeeze was never in a frame.
 *
 * The owner's answer (#1217) is an ADAPTIVE grid — `libraryGridColumns`, the one
 * carrier this test's book area goes through — where a narrow pane keeps the
 * tiles at the canonical poster's width and opens a second column only when two
 * of them plus the gap fit inside the padded content box, i.e. from
 * 2 × 120 + 12 + 32 = 284 dp of pane. These assertions pin that policy from both
 * sides, on BOUNDS and on STRUCTURE rather than on pixels:
 *  - at 600 dp every tile is at least `PosterWidth` wide and stays inside the
 *    pane, the books are drawn as TILES and not as the list the chip does not
 *    say, and the pane states that it can only hold one column;
 *  - at 840 dp the two columns are still there, still inside the pane, still
 *    tiles, and no one-column note is shown.
 *
 * The frames this class also writes (`library-grid-600dp.png`,
 * `library-grid-840dp.png`) are the reviewable half of the same evidence; the
 * geometry above is the half that fails on a regression.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w600dp-h1000dp-420dpi", sdk = [36])
class LibraryGridBreakpointTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Exactly the wide window's own breakpoint: 600 dp of window, so 239.6 dp
     * of list pane and one tile column.
     */
    @Test
    fun at600ThePaneKeepsTilesAtTheCanonicalPosterWidthInOneColumn() {
        showLibraryPane()

        val pane = paneBounds()
        val paneWidth = pane.right.value - pane.left.value
        assertTrue(
            "the fixture must be the defect's own window: the list pane of a 600 dp " +
                "window is ${paneWidth} dp, under the $TwoColumnMinWidthDp dp a second " +
                "tile column needs",
            paneWidth < TwoColumnMinWidthDp
        )

        val cards = bookCards()
        // The grid is LAZY, so this is "what the viewport composed", not "how
        // many books the fixture has" — and it is deliberately low, so that a
        // regression fails on the SHAPE below rather than on the count.
        assertTrue("the fixture must lay out books", cards.size >= 2)

        cards.forEach { card ->
            assertTrue(
                "no book card may leave the pane: card=[${card.left},${card.right}] " +
                    "pane=[${pane.left},${pane.right}]",
                card.left.value >= pane.left.value - Tolerance &&
                    card.right.value <= pane.right.value + Tolerance
            )
            val width = card.right.value - card.left.value
            assertTrue(
                "a tile may not be squeezed under the canonical poster " +
                    "(${PosterWidth.value} dp): one measured $width dp. Two columns in " +
                    "this $paneWidth dp pane would be " +
                    "${(paneWidth - 44f) / 2} dp each — the adaptive grid must open ONE " +
                    "column of full-width tiles instead",
                width >= PosterWidth.value - Tolerance
            )
        }

        assertEquals(
            "the pane cannot hold a second column, so the tiles must be one per row",
            cards.size,
            rowTops(cards).size
        )

        // The chip in the filter sheet says «Сітка»: the pane must draw tiles
        // and not the list behind its back. The dense row's own progress
        // marker is the one thing a tile never carries — a ONE-column grid of
        // tiles is stacked exactly like the list, so bounds alone cannot tell
        // them apart.
        assertTrue(
            "the grid choice must draw TILES, not the list: no card may carry the " +
                "dense row's progress marker",
            rowProgressMarkers().isEmpty()
        )
        composeTestRule.onNodeWithTag("library_grid_single_column_note").assertExists()

        composeTestRule.onRoot().captureRoboImage(filePath = Frame600Dp)
    }

    /**
     * The contrast case: the same grid on the width the golden set already
     * covers, where a second column DOES fit — 0.4 × (840 − 1) = 335.6 dp.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun at840TheTwoTileColumnsStillFitAndEveryCardStaysInsideThePane() {
        showLibraryPane()

        val pane = paneBounds()
        val paneWidth = pane.right.value - pane.left.value
        assertTrue(
            "the contrast fixture must afford a second column: the list pane of an " +
                "840 dp window is ${paneWidth} dp",
            paneWidth >= TwoColumnMinWidthDp
        )

        val cards = bookCards()
        assertTrue("the fixture must lay out books", cards.size >= 4)

        cards.forEach { card ->
            assertTrue(
                "no book card may leave the pane: card=[${card.left},${card.right}] " +
                    "pane=[${pane.left},${pane.right}]",
                card.left.value >= pane.left.value - Tolerance &&
                    card.right.value <= pane.right.value + Tolerance
            )
            val width = card.right.value - card.left.value
            assertTrue(
                "a tile in a pane that affords the grid must keep the canonical poster " +
                    "width (${PosterWidth.value} dp), measured $width dp",
                width >= PosterWidth.value - Tolerance
            )
        }

        assertEquals(
            "two tiles must still share every row of the grid at 840 dp",
            (cards.size + 1) / 2,
            rowTops(cards).size
        )

        assertTrue(
            "a pane that affords two columns must draw TILES, not the list",
            rowProgressMarkers().isEmpty()
        )
        composeTestRule.onNodeWithTag("library_grid_single_column_note").assertDoesNotExist()

        composeTestRule.onRoot().captureRoboImage(filePath = Frame840Dp)
    }

    /**
     * The screen's own book area, inside the list pane of a wide window — the
     * same two composables `MainActivity` wires together, so the geometry
     * measured here is the geometry the app ships.
     */
    private fun showLibraryPane() {
        val entries = libraryGridEntries(
            // The «Книги» tab always builds this shape (LibraryScreen.kt).
            browsing = false,
            gridMode = true,
            visible = fixtureLibrary,
            continueBook = null,
            denseTitle = "Усі"
        )
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WideDetailPane(
                        list = {
                            LibraryBookGrid(
                                entries = entries,
                                browsing = false,
                                gridMode = true,
                                availability = emptyMap(),
                                downloadCounts = emptyMap(),
                                restoreFocusBookId = null,
                                bookReturnFocusRequester = remember { FocusRequester() },
                                awaitingSubmissionBookIds = emptySet(),
                                watchingSubmissionBookIds = emptySet(),
                                deferredPublicationBookIds = emptySet(),
                                onBookClick = {},
                                onPlayClick = {},
                                onRecheck = {}
                            )
                        },
                        detail = { Box(modifier = Modifier.fillMaxSize()) }
                    )
                }
            }
        }
    }

    private fun paneBounds(): DpRect =
        composeTestRule.onNodeWithTag("wide_detail_list").getUnclippedBoundsInRoot()

    /** Every laid-out book card, whichever id it carries. */
    private fun bookCards(): List<DpRect> {
        val matcher = SemanticsMatcher("testTag starts with library_book_item_") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?.startsWith("library_book_item_") == true
        }
        return composeTestRule.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .map { tag ->
                composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
            }
    }

    /**
     * The `library_row_progress_<id>` tags of the laid-out DENSE ROWS.
     *
     * `LibraryDenseRow` draws that hairline for every started book and nothing
     * else does, so the tag is the one marker that separates "the list" from
     * "tiles" — including from a ONE-column grid of tiles, which is stacked
     * exactly like the list. It has to be read from the UNMERGED tree: the row
     * clears its descendants' semantics for TalkBack.
     */
    private fun rowProgressMarkers(): List<String> {
        val matcher = SemanticsMatcher("testTag starts with library_row_progress_") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?.startsWith("library_row_progress_") == true
        }
        return composeTestRule.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
    }

    /** The distinct tops of the laid-out cards: one per grid row. */
    private fun rowTops(cards: List<DpRect>): Set<Int> =
        cards.map { it.top.value.roundToInt() }.toSet()

    private companion object {
        /**
         * 2 × the canonical poster + the grid's gap + its two 16 dp side
         * paddings — the arithmetic the ticket files as 284 dp, and the width
         * from which the adaptive grid opens its second column.
         */
        const val TwoColumnMinWidthDp = 284f

        /** Sub-pixel slack: Robolectric lays out in pixels, the assertions in dp. */
        const val Tolerance = 0.5f

        /** The band no golden covered before #1206: the defect's own window. */
        const val Frame600Dp = "src/test/snapshots/library-grid-600dp.png"

        /** The contrast frame: the same grid where a second column DOES fit. */
        const val Frame840Dp = "src/test/snapshots/library-grid-840dp.png"
    }
}

/**
 * Four books, deterministic and cover-less, every one STARTED: enough for two
 * tile rows in a pane that affords them, long titles so a squeezed tile would
 * be visible as an ellipsis rather than as a crop, and progress rows so the
 * assertions can tell a dense row from a tile by its own marker.
 */
private val fixtureLibrary: List<LibraryBook> by lazy {
    val template = TestDataFactory.dataBooks().first()
    val entities = (1..4).map { index ->
        template.copy(
            id = "grid-fixture-$index",
            title = "Книга $index з довгою назвою",
            author = "Автор $index",
            coverImageUrl = null,
            coverDrawableRes = 0,
            totalDurationSeconds = 36_000L
        )
    }
    // EVERY fixture book is started: the dense row draws its own
    // `library_row_progress_<id>` hairline only for a started book, and that
    // tag is the marker the assertions use to tell a row from a tile.
    buildLibraryBooks(
        entities,
        entities.mapIndexed { index, entity ->
            PlaybackProgressEntity(
                editionId = "edition-${entity.id}",
                bookId = entity.id,
                currentChapterIndex = 1,
                currentPositionSeconds = 3_600L * (index + 1),
                lastListenedAt = TestDataFactory.FIXED_CLOCK_MS,
                isCompleted = false
            )
        },
        emptyMap()
    )
}
