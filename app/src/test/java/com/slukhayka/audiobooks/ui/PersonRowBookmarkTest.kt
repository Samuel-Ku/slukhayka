package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.ui.screens.PersonRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #400 — long-press on a bookmarked PersonRow opens a context menu with a
 * notifyEnabled toggle; the bookmark itself is NEVER deleted from this menu.
 *
 * Uses [PersonRow] directly (no MainViewModel, no network, no Room).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonRowBookmarkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val person = CatalogPerson(
        name = "Тарас Шевченко",
        path = "/xfsearch/avtor/taras-shevchenko/",
        bookCount = 5,
        role = PersonRole.AUTHOR
    )

    @Test
    fun longPressOpensContextMenuOnBookmarkedPerson() {
        var toggleBookmarkCalled = false
        var toggleNotifyValue: Boolean? = null

        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        PersonRow(
                            person = person,
                            isBookmarked = true,
                            onClick = {},
                            onToggleBookmark = { toggleBookmarkCalled = true },
                            onToggleNotify = { enabled -> toggleNotifyValue = enabled }
                        )
                    }
                }
            }
        }

        // Long-press to open context menu
        composeTestRule.onNodeWithTag("person_${person.path.hashCode()}")
            .performTouchInput { longClick(center) }

        // Menu should appear with "Вимкнути повідомлення"
        composeTestRule.onNodeWithText("Вимкнути повідомлення")
            .assertIsDisplayed()

        // Tap the toggle
        composeTestRule.onNodeWithText("Вимкнути повідомлення")
            .performClick()

        // notifyEnabled was toggled
        assertEquals(false, toggleNotifyValue)
        // Bookmark was NOT deleted
        assertFalse("Long-press toggle must not delete the bookmark", toggleBookmarkCalled)
    }

    @Test
    fun longPressContextMenuOnUnbookmarkedPersonOffersAddBookmark() {
        var toggleBookmarkCalled = false
        var toggleNotifyValue: Boolean? = null

        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        PersonRow(
                            person = person,
                            isBookmarked = false,
                            onClick = {},
                            onToggleBookmark = { toggleBookmarkCalled = true },
                            onToggleNotify = { enabled -> toggleNotifyValue = enabled }
                        )
                    }
                }
            }
        }

        // Long-press to open context menu
        composeTestRule.onNodeWithTag("person_${person.path.hashCode()}")
            .performTouchInput { longClick(center) }

        // Menu should offer "Додати закладку"
        composeTestRule.onNodeWithText("Додати закладку")
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Додати закладку")
            .performClick()

        // Bookmark was added
        assertTrue(toggleBookmarkCalled)
        // notifyEnabled was NOT touched
        assertNull("Unbookmarked long-press should not toggle notify", toggleNotifyValue)
    }

    @Test
    fun bookmarkedRowShowsBookmarkIndicator() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        PersonRow(
                            person = person,
                            isBookmarked = true,
                            onClick = {}
                        )
                    }
                }
            }
        }

        // Bookmark indicator text should be in the merged tree
        composeTestRule.onNodeWithText("Закладено · повідомлення", useUnmergedTree = true)
            .assertExists()
    }
}
