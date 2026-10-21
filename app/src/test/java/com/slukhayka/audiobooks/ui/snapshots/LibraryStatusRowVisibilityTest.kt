package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.SHEET_FILTERS
import com.slukhayka.audiobooks.ui.library.STATUS_FILTERS
import com.slukhayka.audiobooks.ui.screens.LibraryStatusRow
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
 * #1165 — the SELECTED status chip must be readable in full.
 *
 * #390 fixed the same «Завантажені» chip on the Library by eye and left no
 * test behind, so the «Нічна бібліотека» redesign (#885) put the defect back:
 * the row is wider than the window, and a selection that scrolls the line to
 * its start leaves the LAST chip — the highlighted, active filter — under the
 * right window edge. The screenshot in the ticket shows exactly that: «All ·
 * New · Listening · Finished · D…», with «Downloaded» cut mid-glyph.
 *
 * A golden image cannot hold that invariant — re-recording it blesses whatever
 * is rendered. So this test asserts GEOMETRY instead: the selected chip's
 * rectangle must lie inside the row's viewport, and it must be as wide as it
 * draws (a clipped node is a narrower node).
 *
 * The fixture is the shipped chrome at the shipped width (Pixel 8 qualifiers,
 * dark theme, the real trailing «Фільтр» launcher), so the numbers it measures
 * are the device's numbers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibraryStatusRowVisibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Every one-tap status, in turn: whichever chip carries the selection is
    // the one the listener has to be able to read. The walk goes FORWARD and
    // then BACK — a forward-only walk only ever asks the row to scroll right,
    // so the left half of the reveal (coming home to «Усі» after the far end)
    // would never run.
    @Test
    fun every_selected_status_chip_lies_inside_the_row() {
        val selected = mutableStateOf(LibraryFilter.ALL)
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                StatusRowFixture(selected = selected.value, onSelect = { selected.value = it })
            }
        }
        composeTestRule.waitForIdle()

        (STATUS_FILTERS + STATUS_FILTERS.reversed()).forEach { filter ->
            composeTestRule.runOnIdle { selected.value = filter }
            composeTestRule.waitForIdle()
            assertChipInsideTheRow(filter, "library_status_${filter.name.lowercase()}")
        }
    }

    // The rare-filter launcher rides the same line: while IT is the active
    // filter its own label must be readable too — the row used to jump to the
    // far end for that case, and nothing pinned it either.
    @Test
    fun the_selected_rare_filter_launcher_lies_inside_the_row() {
        val selected = mutableStateOf(LibraryFilter.ALL)
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                StatusRowFixture(selected = selected.value, onSelect = { selected.value = it })
            }
        }
        composeTestRule.waitForIdle()

        SHEET_FILTERS.forEach { filter ->
            composeTestRule.runOnIdle { selected.value = filter }
            composeTestRule.waitForIdle()
            assertChipInsideTheRow(filter, TRAILING_TAG)
        }
    }

    // #1165 review — the trip BACK from the launcher. The launcher sits at the
    // far end of the line, so a status picked after it has to be revealed by
    // scrolling the row LEFT, and the walk above never asked for that. «Усі»
    // is the longest such trip — and the chip the golden shows leaving the row
    // when the far end is revealed.
    @Test
    fun a_status_picked_after_a_rare_filter_lies_inside_the_row() {
        val selected = mutableStateOf(LibraryFilter.ALL)
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                StatusRowFixture(selected = selected.value, onSelect = { selected.value = it })
            }
        }
        composeTestRule.waitForIdle()

        val rareFilter = SHEET_FILTERS.last()
        composeTestRule.runOnIdle { selected.value = rareFilter }
        composeTestRule.waitForIdle()
        assertChipInsideTheRow(rareFilter, TRAILING_TAG)

        STATUS_FILTERS.forEach { filter ->
            composeTestRule.runOnIdle { selected.value = filter }
            composeTestRule.waitForIdle()
            assertChipInsideTheRow(filter, "library_status_${filter.name.lowercase()}")
        }
    }

    // The listener who reported this reads English chrome («Downloaded»), and
    // the EN labels are a different width — the invariant is not a UK accident.
    @Test
    @Config(qualifiers = "en-rUS-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
    fun the_downloaded_chip_lies_inside_the_row_in_english() {
        val selected = mutableStateOf(LibraryFilter.ALL)
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                StatusRowFixture(selected = selected.value, onSelect = { selected.value = it })
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { selected.value = LibraryFilter.DOWNLOADED }
        composeTestRule.waitForIdle()
        assertChipInsideTheRow(LibraryFilter.DOWNLOADED, "library_status_downloaded")
    }

    // The visible delta as a picture (ADR-0017): the row the screen draws, with
    // «Завантажені» selected — the very chip the ticket's screenshot shows cut
    // mid-glyph. The assertion above is the gate; this golden is what a
    // reviewer looks at, and it is exactly what #390's eye-only fix lacked.
    @Test
    fun the_downloaded_chip_selected_is_drawn_in_full() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                StatusRowFixture(selected = LibraryFilter.DOWNLOADED, onSelect = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ROW_TAG).captureRoboImage(
            filePath = "src/test/snapshots/library_status_row_downloaded.png"
        )
    }

    /**
     * The invariant #390 never wrote down: the chip's rectangle is inside the
     * row's viewport, and the chip is not narrowed by a clip.
     *
     * The containment is read off the UNCLIPPED rect on purpose: a clipped
     * node's own bounds stop at the edge that hides it, so `chip.right <=
     * row.right` would hold for a chip that is half off-screen (measured: a
     * «Завантажені» that sticks 53 dp past the row still reported its right
     * edge AT the row's edge). The width comparison is the second net: a node
     * the row cuts is a narrower node.
     */
    private fun assertChipInsideTheRow(filter: LibraryFilter, tag: String) {
        val row = composeTestRule.onNodeWithTag(ROW_TAG).getBoundsInRoot()
        val drawn = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val visible = composeTestRule.onNodeWithTag(tag).getBoundsInRoot()
        val where = "«${filter.name}» ($tag): drawn=$drawn visible=$visible row=$row"

        assertTrue("the selected chip runs past the row's right edge — $where", drawn.right <= row.right)
        assertTrue("the selected chip starts before the row's left edge — $where", drawn.left >= row.left)
        assertEquals(
            "the selected chip is narrower than it draws — it is clipped — $where",
            (drawn.right - drawn.left).value,
            (visible.right - visible.left).value,
            0.5f
        )
    }

    /**
     * The shipped row: the same call the Library makes, with the same trailing
     * rare-filter launcher (its parameters mirror `libraryChrome` in
     * `LibraryScreen.kt`), on a full-size dark surface.
     */
    @Composable
    private fun StatusRowFixture(selected: LibraryFilter, onSelect: (LibraryFilter) -> Unit) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                LibraryStatusRow(
                    selected = selected,
                    onSelect = onSelect,
                    scrollState = rememberScrollState(),
                    trailing = {
                        val sheetFilterActive = selected in SHEET_FILTERS
                        FilterChip(
                            selected = sheetFilterActive,
                            onClick = {},
                            label = {
                                Text(
                                    if (sheetFilterActive) {
                                        stringResource(selected.labelRes)
                                    } else {
                                        stringResource(R.string.lib_filter)
                                    }
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = sheetFilterActive,
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag(TRAILING_TAG)
                        )
                    }
                )
            }
        }
    }

    private companion object {
        const val ROW_TAG = "library_status_row"
        const val TRAILING_TAG = "library_status_trailing"
    }
}
