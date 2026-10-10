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
 * #1206 — the Медіатека's book grid at the wide-window breakpoint, as GEOMETRY.
 *
 * The defect the ticket files: a wide window at exactly its 600 dp breakpoint
 * hands the Library's list pane 0.4 of its width, and the two-column tile grid
 * has to live inside that. The pane is 239.6 dp of a 600 dp window (0.4 of
 * 600 − 1 dp for the divider), while two tiles at the canonical poster width
 * plus the gap and the grid's own side padding need
 * 2 × 120 + 12 + 32 = **284 dp**. Nothing in the golden set rendered that band:
 * the fixtures are 411 dp (a phone) and 840 dp (a tablet), so the squeeze was
 * never in a frame.
 *
 * These assertions are on BOUNDS and on STRUCTURE, not on pixels, and they say
 * two things at once, which is what makes the boundary visible from both sides:
 *  - at 600 dp no book card may leave the pane, and no card may be squeezed
 *    under the canonical poster — the two tile columns do not fit, so the grid
 *    degrades to the list, says so in the pane, and every card carries the
 *    dense row's own `library_row_progress_<id>` marker. That marker is what
 *    separates the LIST from a ONE-column grid of tiles, which is stacked
 *    exactly the same way and would otherwise pass every geometry assertion;
 *  - at 840 dp the two tile columns are still there, still inside the pane, no
 *    card is a row and no degradation note is shown.
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
     * of list pane.
     */
    @Test
    fun at600ThePaneCannotHoldTwoTileColumnsSoTheBooksStayInsideItAsAList() {
        showLibraryPane()

        val pane = paneBounds()
        val paneWidth = pane.right.value - pane.left.value
        assertTrue(
            "the fixture must be the defect's own window: the list pane of a 600 dp " +
                "window is ${paneWidth} dp, under the $LibraryGridFloorDp dp two tile " +
                "columns need",
            paneWidth < LibraryGridFloorDp
        )

        val cards = bookCards()
        // The grid is LAZY, so this is "what the viewport composed", not "how
        // many books the fixture has" — and it is deliberately low: a
        // regression that draws tall one-column tiles composes fewer of them,
        // and the assertions below must fail on the SHAPE, not on the count.
        assertTrue("the fixture must lay out books", cards.size >= 2)

        cards.forEach { card ->
            assertTrue(
                "no book card may leave the pane: card=[${card.left},${card.right}] " +
                    "pane=[${pane.left},${pane.right}]",
                card.left.value >= pane.left.value - Tolerance &&
                    card.right.value <= pane.right.value + Tolerance
            )
        }

        cards.forEach { card ->
            val width = card.right.value - card.left.value
            assertTrue(
                "no book card may be squeezed under the canonical poster width " +
                    "(${PosterWidth.value} dp): a card measured $width dp — the two " +
                    "tile columns need $LibraryGridFloorDp dp of pane, this pane has " +
                    "$paneWidth dp, so the books must be presented as a list instead",
                width >= PosterWidth.value - Tolerance
            )
        }

        assertEquals(
            "with the two tile columns unaffordable the books must be one per row " +
                "(a list), not squeezed into a single column of tiles",
            cards.size,
            rowTops(cards).size
        )

        composeTestRule.onNodeWithTag("library_grid_degraded_note").assertExists()
        assertEquals(
            "every card must be the dense ROW the list mode draws, not a tile: a " +
                "ONE-column grid of tiles is stacked as well, so 'one per row' alone " +
                "cannot tell the two apart — the row's own progress marker can",
            cards.size,
            rowProgressMarkers().size
        )

        composeTestRule.onRoot().captureRoboImage(filePath = Frame600Dp)
    }

    /**
     * The contrast case: the same pane rule on the width the golden set already
     * covers, where two tiles DO fit — 0.4 × (840 − 1) = 335.6 dp.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun at840TheTwoTileColumnsStillFitAndEveryCardStaysInsideThePane() {
        showLibraryPane()

        val pane = paneBounds()
        val paneWidth = pane.right.value - pane.left.value
        assertTrue(
            "the contrast fixture must afford two tile columns: the list pane of an " +
                "840 dp window is ${paneWidth} dp",
            paneWidth >= LibraryGridFloorDp
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

        composeTestRule.onNodeWithTag("library_grid_degraded_note").assertDoesNotExist()
        assertTrue(
            "a pane that affords the grid must draw TILES: the dense rows' own " +
                "progress markers must be absent",
            rowProgressMarkers().isEmpty()
        )

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
         * paddings — the arithmetic the ticket files as 284 dp.
         */
        const val LibraryGridFloorDp = 284f

        /** Sub-pixel slack: Robolectric lays out in pixels, the assertions in dp. */
        const val Tolerance = 0.5f

        /** The band no golden covered before #1206: the defect's own window. */
        const val Frame600Dp = "src/test/snapshots/library-grid-600dp.png"

        /** The contrast frame: the same pane rule where two tiles DO fit. */
        const val Frame840Dp = "src/test/snapshots/library-grid-840dp.png"
    }
}

/**
 * Four books, deterministic and cover-less, every one STARTED: enough for two
 * tile rows in a pane that affords them, long titles so a squeezed tile would
 * be visible as an ellipsis rather than as a crop, and progress rows so each
 * dense row carries the `library_row_progress_<id>` marker the assertions read.
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
