package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.screens.LibraryTabsWithSearch
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.slukhayka.audiobooks.ui.theme.PaperInkThemedContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1627 — F30b's inline band (three EQUAL tabs + the 140 dp search pill in one
 * row) must never ellipsize a tab label at 411 dp. The band is only allowed
 * when three times the WIDEST label fit; otherwise the search takes its own
 * line (the same fallback that already exists below 400 dp).
 *
 * The composition uses the SHIPPED theme (`PaperInkThemedContent`): Медіатека
 * renders under it in MainActivity, so `PaperInkType.Tab` — not the Material
 * title style — is what the listener actually sees.
 *
 * The label is a real Compose text node: `GetTextLayoutResult` exposes
 * `isLineEllipsized(0)`, so this pins the visual fact instead of a screenshot.
 */
abstract class LibraryTabsFitTestBase {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** The three tab labels in the test's locale. */
    protected abstract val labels: List<String>

    private fun setBand(fontScale: Float) {
        composeTestRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = current.density, fontScale = fontScale)
            ) {
                AudiobookTheme(darkTheme = true) {
                    PaperInkThemedContent {
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
        }
    }

    protected fun assertNoEllipsis(fontScale: Float) {
        setBand(fontScale)
        for (label in labels) {
            val layouts = mutableListOf<TextLayoutResult>()
            composeTestRule
                .onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val ellipsized = (0 until layout.lineCount).firstOrNull { layout.isLineEllipsized(it) }
            assertFalse(
                "таб «$label» еліпситься на 411 dp при fontScale $fontScale " +
                    "(line=$ellipsized of ${layout.lineCount}, size=${layout.size}, " +
                    "constraints=${layout.layoutInput.constraints})",
                ellipsized != null
            )
        }
    }

    protected fun tabWidthDp() = composeTestRule
        .onNodeWithTag("library_tab_books", useUnmergedTree = true)
        .getUnclippedBoundsInRoot()
        .let { it.right - it.left }

    @Test
    fun `411 dp keeps every tab whole at fontScale 1_0 and stays inline`() {
        assertNoEllipsis(fontScale = 1.0f)
        assertTrue(
            "at the default fontScale the F30b band must stay inline (equal tabs ≈78 dp), got ${tabWidthDp()}",
            tabWidthDp() < 100.dp
        )
    }

    @Test
    fun `411 dp keeps every tab whole at fontScale 1_15`() {
        assertNoEllipsis(fontScale = 1.15f)
    }

    @Test
    fun `411 dp keeps every tab whole at fontScale 1_5`() {
        assertNoEllipsis(fontScale = 1.5f)
    }

    @Test
    fun `411 dp keeps every tab whole at fontScale 2_0`() {
        assertNoEllipsis(fontScale = 2.0f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryTabsFitTest : LibraryTabsFitTestBase() {
    override val labels = listOf("Книги", "Полиці", "Збережене")

    /** The QA case: at 1.15 «Збережене» cannot share the row, so fallback. */
    @Test
    fun `uk at fontScale 1_15 takes the two-band fallback instead of squeezing`() {
        assertNoEllipsis(fontScale = 1.15f)
        assertTrue(
            "fallback must give the tabs the full width, got ${tabWidthDp()}",
            tabWidthDp() > 120.dp
        )
    }
}

