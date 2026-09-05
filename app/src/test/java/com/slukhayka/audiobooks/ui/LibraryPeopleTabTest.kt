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
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.ui.screens.BookmarkedPersonRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #401 — tests for the Медіатека "Люди" tab components:
 * - BookmarkedPersonRow renders name, role, and notify state
 * - Long-press opens context menu with notifyEnabled toggle
 * - Person click callback fires
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryPeopleTabTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun bookmarkedPersonRowShowsNameAndRole() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        BookmarkedPersonRow(
                            displayName = "Тарас Шевченко",
                            role = PersonRole.AUTHOR,
                            notifyEnabled = true,
                            onClick = {},
                            onToggleNotify = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Тарас Шевченко")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Автор")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Повідомлення увімкнені")
            .assertIsDisplayed()
    }

    @Test
    fun bookmarkedPersonRowShowsNarratorRole() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        BookmarkedPersonRow(
                            displayName = "Ада Роговцева",
                            role = PersonRole.NARRATOR,
                            notifyEnabled = false,
                            onClick = {},
                            onToggleNotify = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Ада Роговцева")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Виконавець")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Повідомлення вимкнені")
            .assertIsDisplayed()
    }

    @Test
    fun longPressOpensContextMenuAndTogglesNotify() {
        var toggleValue: Boolean? = null

        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        BookmarkedPersonRow(
                            displayName = "Олесь Гончар",
                            role = PersonRole.AUTHOR,
                            notifyEnabled = true,
                            onClick = {},
                            onToggleNotify = { enabled -> toggleValue = enabled }
                        )
                    }
                }
            }
        }

        // Long-press to open context menu
        composeTestRule.onNodeWithTag("bookmarked_person_${"Олесь Гончар".hashCode()}")
            .performTouchInput { longClick(center) }

        // Menu should show "Вимкнути повідомлення"
        composeTestRule.onNodeWithText("Вимкнути повідомлення")
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Вимкнути повідомлення")
            .performClick()

        // Verify callback fired
        assertEquals(false, toggleValue)
    }

    @Test
    fun clickFiresPersonClickCallback() {
        var clicked = false

        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        BookmarkedPersonRow(
                            displayName = "Леся Українка",
                            role = PersonRole.AUTHOR,
                            notifyEnabled = true,
                            onClick = { clicked = true },
                            onToggleNotify = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Леся Українка")
            .performClick()

        assertTrue("Click should fire person click callback", clicked)
    }
}
