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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.screens.LibraryBookCard
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F30a — the Медіатека list row is the canonical COVER row (BookRow 58×87,
 * 112 dp), and it keeps #1165's contract: the interaction area is the WHOLE
 * row.
 *
 * The #1165 defect («the title is cut off by the badge») was never a cut
 * title — it was a press/focus indication ending over the trailing column.
 * The canonical row puts the click on the row's own node, so the two things a
 * golden cannot hold are asserted as GEOMETRY: the rectangle that owns the
 * click must be the row's box, and the marks the ticket named (here the
 * offline badge over the returned cover) must sit inside it.
 *
 * A golden is deliberately not the proof: the row is photographed without a
 * press, so re-recording an image would bless whatever geometry it drew.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibraryListRowInteractionBoundsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * The ticket's shape in one row: a book that was never played («новий»)
     * and is fully on disk (the cloud on the cover) — the marks the reporter
     * read as squeezing the title. No progress row: that is what makes it new.
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
            progressList = emptyList(),
            chaptersByBook = emptyMap()
        ).single()
    }

    @Test
    fun the_interaction_area_is_the_whole_row_and_the_badge_sits_inside_it() {
        showTheRow()

        val interaction = rowBounds()
        val drawn = composeTestRule
            .onNodeWithTag(ROW_TAG, useUnmergedTree = true)
            .getBoundsInRoot()
        val container = composeTestRule.onRoot(useUnmergedTree = true).getBoundsInRoot()
        val title = composeTestRule
            .onNodeWithTag(TITLE_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val cloud = composeTestRule
            .onNodeWithTag(CLOUD_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        val where = "interaction=${describe(interaction)} title=${describe(title)} " +
            "cloud=${describe(cloud)} container=${describe(container)}"

        assertTrue(
            "the offline badge falls outside the interaction area — the row's " +
                "click stops before the mark — $where",
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
     * F30a's other half: the cover is BACK. The paper row returns a 58×87
     * cover before the text column and keeps the row inside the direction's
     * 112 dp (a delta of one divider is not a defect). A title that starts at
     * the old dense-row 20 dp would mean the cover slot is empty again.
     */
    @Test
    fun the_row_returns_the_58_dp_cover_and_stays_within_120_dp() {
        showTheRow()

        val row = rowBounds()
        val title = composeTestRule
            .onNodeWithTag(TITLE_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the list row is ${row.height} tall; the direction row is 112 dp",
            row.height.value >= 104f && row.height.value <= 120f
        )
        // 20 dp page padding + the 58 dp cover + its 12 dp gap.
        assertTrue(
            "the title starts at ${title.left}, not after a cover — the 58 dp " +
                "cover slot is missing",
            title.left.value >= 20f + 58f + 12f - TOLERANCE.value
        )
    }

    /**
     * The cover row is still ONE contextual action whose description is the
     * book: removing the dense row must not cost the row-body node that
     * TalkBack and the journeys click.
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

    /** The shipped row: paper theme, full width, full-size surface. */
    @Composable
    private fun TheRow() {
        PaperInkTheme(darkTheme = true) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LibraryBookCard(
                        book = newAndDownloaded,
                        grid = false,
                        onClick = {}
                    )
                }
            }
        }
    }

    private fun showTheRow() {
        composeTestRule.setContent { TheRow() }
        composeTestRule.waitForIdle()
    }

    private fun rowBounds(): DpRect = composeTestRule
        .onNodeWithTag(ROW_TAG, useUnmergedTree = true)
        .getUnclippedBoundsInRoot()

    private fun width(rect: DpRect): Float = (rect.right - rect.left).value

    private fun describe(rect: DpRect): String =
        "[${rect.left}, ${rect.top} .. ${rect.right}, ${rect.bottom}]"

    private companion object {
        const val BOOK_ID = "issue-f30a-row"
        const val TITLE = "Сходами вниз: довга назва, яку видно повністю"
        const val AUTHOR = "Дон Куін"

        /** The row's own box is the anchor — a dp of rounding is not a defect. */
        val TOLERANCE = 1.dp

        val ROW_TAG = "library_book_item_$BOOK_ID"
        val TITLE_TAG = "library_book_row_${BOOK_ID}_title"
        val CLOUD_TAG = "library_offline_badge_$BOOK_ID"
    }
}
