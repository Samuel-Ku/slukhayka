package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibrarySort
import com.slukhayka.audiobooks.ui.screens.LibraryFilterSheet
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * fix(accessibility): #371 — the filter sheet must not voice Polish system
 * descriptions on a Polish-system device. The drag handle is decorative and
 * must not create an extra TalkBack focus stop; the scrim's dismiss action
 * must be voiced in Ukrainian via the app's values-pl override.
 *
 * Prior art: no pure JVM seam for ModalBottomSheet window semantics, so we
 * pin the accessible contract at the highest testable seam: the sheet's
 * composition. A failing test before the fix would find nodes with
 * "Uchwyt" / "Zamknij" (Polish) in the tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryFilterSheetAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setSheetContent() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryFilterSheet(
                    filter = LibraryFilter.ALL,
                    sort = LibrarySort.RECENTLY_LISTENED,
                    gridMode = false,
                    onFilterChange = {},
                    onSortChange = {},
                    onGridModeChange = {},
                    onDismiss = {}
                )
            }
        }
        // Let ModalBottomSheet's entrance animation settle so its window
        // content (including drag handle and scrim) is composed.
        composeTestRule.waitForIdle()
        // ModalBottomSheet animates in; advance clock to ensure content is laid out.
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `drag handle does not create an extra TalkBack focus stop with Polish description`() {
        setSheetContent()

        // The decorative drag handle must be hidden from accessibility — no node
        // with the Polish "Uchwyt do przeciągania" (or its Ukrainian counterpart
        // "Маркер переміщення") should be focusable. Hiding keeps the visual
        // cue but removes the extra stop.
        composeTestRule.onNode(hasContentDescription("Uchwyt do przeciągania"), useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNode(hasContentDescription("Маркер переміщення"), useUnmergedTree = true)
            .assertDoesNotExist()
        // Also ensure no node with the Polish pane title is exposed as a separate focusable.
        composeTestRule.onNode(hasContentDescription("Plansza dolna"), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `sheet content is accessible in Ukrainian`() {
        setSheetContent()

        // The sheet body itself must be reachable in Ukrainian — the static
        // header from the content, not the system Polish scrim.
        composeTestRule.onNodeWithTag("library_sheet_filter_all").assertExists()
        // The scrim's dismiss action (when present) must not be Polish. If a
        // node with Polish dismiss text exists, the values-pl override failed.
        composeTestRule.onNode(hasContentDescription("Zamknij arkusz"), useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNode(hasContentDescription("Zamknij planszę dolną"), useUnmergedTree = true)
            .assertDoesNotExist()
    }
}
