package com.slukhayka.audiobooks.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1206 — the Медіатека grid's container floor, as pure arithmetic.
 *
 * The tile grid is drawn in two different containers of the same screen: the
 * whole window on a phone, and 0.4 of it inside a wide window's list pane. Only
 * the second one is ever too narrow, and the boundary between "draw the tiles"
 * and "draw the list" is a number, so it is a test rather than something only a
 * device can answer — the same reason `windowLayoutFor` is pure.
 */
class LibraryGridPolicyTest {

    @Test
    fun twoTileColumnsNeedTwoCanonicalPostersPlusTheGapAndThePadding() {
        assertEquals(
            "2 × the canonical poster (120 dp) + the column gap (12 dp) + the " +
                "grid's two 16 dp side paddings",
            284.dp,
            LibraryGridMinWidth
        )
    }

    @Test
    fun theFloorIsTheBoundaryItselfNotAnApproximation() {
        assertFalse("283 dp is under the floor", libraryGridShowsTiles(283.dp, gridMode = true))
        assertTrue("284 dp is the floor itself", libraryGridShowsTiles(284.dp, gridMode = true))
    }

    @Test
    fun theTwoWindowsTheTicketNamesAnswerOnOppositeSidesOfTheFloor() {
        // 0.4 × (600 − 1 dp divider): the list pane of a 600 dp window, where
        // the tiles would be forced to (239.62 − 44) / 2 = 97.81 dp.
        assertFalse(
            "the 600 dp window's list pane cannot hold the tiles",
            libraryGridShowsTiles(239.62.dp, gridMode = true)
        )
        // 0.4 × (840 − 1 dp divider): the pane of the 840 dp golden fixture.
        assertTrue(
            "the 840 dp window's list pane can",
            libraryGridShowsTiles(335.6.dp, gridMode = true)
        )
    }

    @Test
    fun aPhoneWindowKeepsTheTilesItHasAlwaysHad() {
        // 411 dp is the golden fixture's phone width and the width the
        // library_redesign_grid golden was recorded at.
        assertTrue(libraryGridShowsTiles(411.dp, gridMode = true))
        // 320 dp is Robolectric's default window: (320 − 44) / 2 = 138 dp per
        // tile, still above the canonical poster, so it must not degrade either.
        assertTrue(libraryGridShowsTiles(320.dp, gridMode = true))
    }

    @Test
    fun theListenersListChoiceIsNeverUpgradedToTiles() {
        assertFalse(
            "a listener who chose the list must never get tiles, however wide the window",
            libraryGridShowsTiles(2560.dp, gridMode = false)
        )
    }
}
