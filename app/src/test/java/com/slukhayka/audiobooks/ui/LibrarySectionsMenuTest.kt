package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.LibraryHeaderActionsInner
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * spec-54 T04/T06 (#872/#873) — the three subsections live in the ⋮ menu of
 * «Мої книги», and the statistics entry keeps leading to the SAME feature
 * instead of disappearing during the transition (the T16 «Мій рік» will take
 * over the same entry).
 */
@RunWith(RobolectricTestRunner::class)
class LibrarySectionsMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var openedSection: Int? = null
    private var openedShelves = 0

    private fun render(counts: Pair<Int, Int> = 3 to 2) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                val focus = remember { FocusRequester() }
                Column {
                    LibraryHeaderActionsInner(
                        bookmarksCount = counts.first,
                        peopleCount = counts.second,
                        searchExpanded = false,
                        menuOpen = true,
                        onToggleSearch = {},
                        onMenuOpenChange = {},
                        onOpenSection = { openedSection = it },
                        onOpenShelves = { openedShelves++ },
                        onAdd = {},
                        importFocusRequester = focus
                    )
                }
            }
        }
    }

    @Test
    fun theMenuOffersShelvesSavedAndStatistics() {
        render()

        composeTestRule.onNodeWithTag("library_section_shelves").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_section_saved").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_section_stats").assertIsDisplayed()
        // The counts are the listener's own facts, summed for the merged section.
        composeTestRule.onNodeWithText("Збережене (5)").assertIsDisplayed()
    }

    @Test
    fun eachEntryLeadsToItsOwnPlace() {
        render()

        composeTestRule.onNodeWithTag("library_section_shelves").performClick()
        assertEquals("Полиці open the existing collections", 1, openedShelves)

        composeTestRule.onNodeWithTag("library_section_saved").performClick()
        assertEquals("Збережене is section 1", 1, openedSection)

        composeTestRule.onNodeWithTag("library_section_stats").performClick()
        assertEquals(
            "статистика лишається доступною з «Моїх книг» (#872)",
            2,
            openedSection
        )
    }
}
