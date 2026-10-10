package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.components.PosterWidth
import com.slukhayka.audiobooks.ui.theme.AppDimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1206 — the Медіатека grid's column rule where the ticket found the defect, as
 * pure arithmetic.
 *
 * The tile grid is drawn in two different containers of the same screen: the
 * whole window on a phone, and a fraction of it inside a wide window's list
 * pane. Only the second one is ever too narrow for a second column, and the
 * boundary between "two columns" and "one column" is a number, so it is a test
 * rather than something only a device can answer.
 *
 * The rule itself is [libraryGridColumns] — the ONE carrier (#1205/#1217) that
 * both the book area and the snapshot harness go through. These assertions ask
 * that rule the same question `LazyVerticalGrid` asks it, so they pin the
 * shipped answer instead of a second copy of it.
 */
class LibraryGridPolicyTest {

    @Test
    fun theShippedRuleIsTheAdaptiveOneWithTheCanonicalPosterAsItsMinimum() {
        assertEquals(
            "the grid choice must be adaptive at the canonical poster's width — " +
                "that is the whole of the #1217 policy",
            GridCells.Adaptive(minSize = PosterWidth),
            libraryGridColumns(gridMode = true)
        )
        assertEquals(
            "the list choice is one column, whatever the width",
            GridCells.Fixed(1),
            libraryGridColumns(gridMode = false)
        )
    }

    @Test
    fun theTwoWindowsTheTicketNamesGetOneAndTwoColumns() {
        assertEquals(
            "the list pane of a 600 dp window is 0.4 × (600 − 1) = 239.62 dp, and " +
                "two columns there would be squeezed to 97.81 dp each — under the " +
                "canonical poster, so the rule must give ONE column",
            1,
            columnCount(239.62.dp)
        )
        assertEquals(
            "the list pane of an 840 dp window is 0.4 × (840 − 1) = 335.6 dp — two " +
                "columns of 145.8 dp, which is what the golden set already pins",
            2,
            columnCount(335.6.dp)
        )
    }

    @Test
    fun aPhoneWindowKeepsTheTwoColumnsItHasAlwaysHad() {
        // 411 dp is the golden fixture's phone width: (411 − 44) / 2 = 183.5 dp
        // per column, exactly what the fixed two-column grid drew.
        assertEquals(2, columnCount(411.dp))
        // 320 dp is Robolectric's default window: (320 − 44) / 2 = 138 dp.
        assertEquals(2, columnCount(320.dp))
    }

    @Test
    fun everyColumnOfEveryReachablePaneKeepsTheCanonicalPosterWidth() {
        // 192 dp is the narrowest pane the app can produce — the mini-player's
        // 360 dp leading column leaves the Library's list pane 0.4 × (840 − 360)
        // on an 840 dp window (#1205) — and a desktop-class window is the
        // widest. The policy is that the COUNT follows the width while the tile
        // never goes under the poster; this is that claim, measured.
        for (paneWidth in listOf(192, 240, 284, 320, 411, 600, 840, 1024, 2560)) {
            val sizes = columnSizes(paneWidth.dp)
            assertTrue(
                "a $paneWidth dp pane gave cells $sizes — every one must keep the " +
                    "canonical poster width (${PosterWidth.value} dp)",
                sizes.all { it >= PosterWidth.value - 0.5f }
            )
        }
    }

    /** How many tile columns [libraryGridColumns] draws in a [paneWidth] pane. */
    private fun columnCount(paneWidth: Dp): Int = columnSizes(paneWidth).size

    /**
     * The cells [libraryGridColumns] hands out, asked the way the grid asks:
     * the content width is the pane minus the grid's own side paddings, and the
     * spacing is the one the grid arranges with.
     */
    private fun columnSizes(paneWidth: Dp): List<Float> =
        with(libraryGridColumns(gridMode = true)) {
            with(UnitDensity) {
                calculateCrossAxisCellSizes(
                    availableSize = (paneWidth - AppDimens.PageSides * 2)
                        .roundToPx()
                        .coerceAtLeast(0),
                    spacing = AppDimens.SpaceMd.roundToPx()
                ).map { it.toFloat() }
            }
        }

    private companion object {
        /** One pixel per dp: every number in this file is written in dp. */
        val UnitDensity = Density(density = 1f, fontScale = 1f)
    }
}
