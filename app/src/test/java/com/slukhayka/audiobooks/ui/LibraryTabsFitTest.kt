package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.screens.LibraryTabsWithSearch
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1627 — F30b's inline band (three tabs + the 140 dp search pill in ONE row)
 * must not ellipsize a tab label at 411 dp / fontScale 1.15.
 *
 * The tab label is a real Compose text node; `GetTextLayoutResult` exposes
 * whether the laid-out paragraph overflowed its width, so this pins the
 * visual fact instead of a screenshot that nobody reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryTabsFitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun assertTabsFit(fontScale: Float) {
        composeTestRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = current.density, fontScale = fontScale)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Box(modifier = Modifier.width(411.dp)) {
                        LibraryTabsWithSearch(
                            booksSelected = true,
                            savedSelected = false,
                            onBooks = {},
                            onShelves = {},
                            onSaved = {},
                            query = "",
                            onQueryChange = {}
                        )
                    }
                }
            }
        }

        println(
            "DENSITY=" + composeTestRule.density +
                " root=" + composeTestRule.onRoot(useUnmergedTree = true).getUnclippedBoundsInRoot() +
                " tabBooks=" + composeTestRule.onNodeWithTag("library_tab_books", useUnmergedTree = true).getUnclippedBoundsInRoot()
        )
        for (label in listOf("Книги", "Полиці", "Збережене")) {
            val layouts = mutableListOf<TextLayoutResult>()
            composeTestRule
                .onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(
                "таб «$label» еліпситься на 411 dp при fontScale $fontScale " +
                    "(bounds=${composeTestRule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()}, " +
                    "size=${layout.size}, c=${layout.layoutInput.constraints}, ellipsized=${layout.isLineEllipsized(0)}, " +
                    "lines=${layout.lineCount})",
                layout.isLineEllipsized(0)
            )
        }
    }

    @Test
    fun `411 dp at fontScale 1_15 keeps every tab on one line`() = assertTabsFit(fontScale = 1.15f)

    @Test
    fun `411 dp at the default fontScale keeps every tab on one line`() = assertTabsFit(fontScale = 1.0f)
}
