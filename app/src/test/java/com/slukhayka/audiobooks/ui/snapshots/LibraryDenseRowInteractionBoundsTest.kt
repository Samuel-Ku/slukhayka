package com.slukhayka.audiobooks.ui.snapshots

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.screens.LibraryDenseRow
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
 * #1165 — the dense library row's interaction area is the WHOLE row.
 *
 * The report read «the title is cut off by the "new" badge». The title is not
 * cut: what has an edge there is the INTERACTION AREA. The clip + `clickable`
 * used to sit on the title/author column, so the press/focus indication ended
 * exactly where the trailing column («новий»/«18 хв» + the offline cloud)
 * begins, and a hard edge through the middle of the row reads as a cut title.
 *
 * A golden cannot hold that invariant — the row is drawn without a press, so
 * re-recording an image blesses whatever is rendered. This test asserts
 * GEOMETRY instead: the rectangle that owns the click (the row's own tagged
 * node) must cover the row's whole box AND the trailing marks the ticket names,
 * on the shipped chrome at the shipped width (Pixel 8, dark theme).
 *
 * The fixture is the row alone, full width, so «the row's box» is the box the
 * row itself is given: what the grid's page padding decides is not the claim
 * under test — where the interaction area ends inside that box is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibraryDenseRowInteractionBoundsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * The ticket's pair in one row: a book that was never played («новий» on
     * the right) and is fully on disk (the cloud beside it) — the two trailing
     * marks the reporter read as squeezing the title.
     */
    private val newAndDownloaded: LibraryBook = run {
        val template = TestDataFactory.dataBooks().first()
        buildLibraryBooks(
            books = listOf(
                template.copy(
                    id = BOOK_ID,
                    title = TITLE,
                    author = AUTHOR,
                    isDownloaded = true
                )
            ),
            // No progress row at all: that is exactly what makes it «новий».
            progressList = emptyList(),
            chaptersByBook = emptyMap()
        ).single()
    }

    /**
     * The boundary the ticket is about: the interaction area must be the whole
     * row, trailing column included. A clipped node stops at the edge that
     * hides it, so the containment is read off the UNCLIPPED rect — a text
     * column narrowed by the row's own clip would still report its right edge
     * at the row's edge.
     */
    @Test
    fun the_interaction_area_covers_the_whole_row_including_the_trailing_column() {
        showTheRow()

        val interaction = composeTestRule
            .onNodeWithTag(ROW_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val drawn = composeTestRule
            .onNodeWithTag(ROW_TAG, useUnmergedTree = true)
            .getBoundsInRoot()
        val container = composeTestRule.onRoot(useUnmergedTree = true).getBoundsInRoot()
        val title = composeTestRule
            .onNodeWithText(TITLE, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val progress = composeTestRule
            .onNodeWithText(progressLabel(), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val cloud = composeTestRule
            .onNodeWithTag(CLOUD_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        val where = "interaction=${describe(interaction)} title=${describe(title)} " +
            "progress=${describe(progress)} cloud=${describe(cloud)} " +
            "container=${describe(container)}"

        assertTrue(
            "the trailing «${progressLabel()}» state word falls outside the " +
                "interaction area — the row's click stops before the badge — $where",
            progress.right <= interaction.right + TOLERANCE
        )
        assertTrue(
            "the offline cloud falls outside the interaction area — the row's " +
                "click stops before the badge — $where",
            cloud.right <= interaction.right + TOLERANCE
        )
        assertTrue(
            "the title starts left of the interaction area — the click does not " +
                "reach the row's left edge — $where",
            title.left >= interaction.left - TOLERANCE
        )
        assertEquals(
            "the interaction area does not start at the row's left edge — it is " +
                "a block inside the row, not the row — $where",
            container.left.value,
            interaction.left.value,
            TOLERANCE.value
        )
        assertEquals(
            "the interaction area does not reach the row's right edge — it is a " +
                "block inside the row, not the row — $where",
            container.right.value,
            interaction.right.value,
            TOLERANCE.value
        )
        assertEquals(
            "the interaction area is narrower than it draws — it is clipped — $where",
            width(interaction),
            width(drawn),
            TOLERANCE.value
        )
    }

    /**
     * Moving the click to the row put the trailing column inside the cleared
     * node, so this pins what the move must NOT cost: the row stays ONE
     * contextual action whose description is the book, exactly as the KDoc of
     * [LibraryDenseRow] promises («one node for the row body»).
     */
    @Test
    fun the_whole_row_is_still_one_action() {
        showTheRow()

        composeTestRule.onNodeWithTag(ROW_TAG, useUnmergedTree = true)
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(
                        ApplicationProvider.getApplicationContext<Context>()
                            .getString(R.string.a11y_library_entry_description, TITLE, AUTHOR)
                    )
                )
            )
    }

    /** The shipped row, at the shipped width, on a full-size dark surface. */
    @Composable
    private fun TheRow() {
        AudiobookTheme(darkTheme = true) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LibraryDenseRow(
                        book = newAndDownloaded,
                        availability = null,
                        onRecheck = {},
                        downloadCount = null,
                        onOpen = {}
                    )
                }
            }
        }
    }

    private fun showTheRow() {
        composeTestRule.setContent { TheRow() }
        composeTestRule.waitForIdle()
    }

    /** The trailing word the row prints for a never-played book — chrome. */
    private fun progressLabel(): String = ApplicationProvider
        .getApplicationContext<Context>()
        .getString(R.string.lib_progress_new)

    private fun width(rect: DpRect): Float = (rect.right - rect.left).value

    private fun describe(rect: DpRect): String =
        "[${rect.left}, ${rect.top} .. ${rect.right}, ${rect.bottom}]"

    private companion object {
        const val BOOK_ID = "issue-1165-row"
        const val TITLE = "Сходами вниз: довга назва, яку видно повністю"
        const val AUTHOR = "Дон Куін"

        /** The row's own box is the anchor — a dp of rounding is not a defect. */
        val TOLERANCE = 1.dp

        val ROW_TAG = "library_book_item_$BOOK_ID"
        val CLOUD_TAG = "library_inline_offline_badge_$BOOK_ID"
    }
}
