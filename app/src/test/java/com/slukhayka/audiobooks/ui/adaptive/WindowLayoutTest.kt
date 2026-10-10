package com.slukhayka.audiobooks.ui.adaptive

import com.slukhayka.audiobooks.ui.SelectedTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #900 — the breakpoint itself, as a test rather than a device.
 *
 * The owner's decision (issue #900, 2026-09-18) is «великі екрани — від
 * ≥600 dp». The two interesting numbers are therefore 599 and 600; the rest
 * pin that nothing else accidentally flips the layout.
 */
class WindowLayoutTest {

    @Test
    fun fiveHundredNinetyNineDpIsStillAPhone() {
        assertEquals(WindowLayout.COMPACT, windowLayoutFor(599))
    }

    @Test
    fun sixHundredDpIsAlreadyAWideWindow() {
        assertEquals(WindowLayout.EXPANDED, windowLayoutFor(600))
    }

    @Test
    fun theBoundaryIsTheOnlyThingThatFlipsTheLayout() {
        assertEquals(WindowLayout.COMPACT, windowLayoutFor(ExpandedMinWidthDp - 1))
        assertEquals(WindowLayout.EXPANDED, windowLayoutFor(ExpandedMinWidthDp))
    }

    @Test
    fun narrowWindowsAreCompact() {
        listOf(0, 320, 360, 411, 480).forEach { widthDp ->
            assertEquals("width=$widthDp", WindowLayout.COMPACT, windowLayoutFor(widthDp))
        }
    }

    @Test
    fun tabletWindowsAreExpanded() {
        listOf(600, 601, 840, 1024, 2560).forEach { widthDp ->
            assertEquals("width=$widthDp", WindowLayout.EXPANDED, windowLayoutFor(widthDp))
        }
    }

    @Test
    fun libraryOpensTheDetailPaneOnlyOnAWideWindowWithABookOpen() {
        assertTrue(
            showsWideDetailPane(
                layout = WindowLayout.EXPANDED,
                tab = SelectedTab.LIBRARY,
                detailOpen = true
            )
        )
        // Phone: the book page replaces the list, exactly as before.
        assertFalse(
            showsWideDetailPane(
                layout = WindowLayout.COMPACT,
                tab = SelectedTab.LIBRARY,
                detailOpen = true
            )
        )
        // Nothing open: there is no page to put beside the list.
        assertFalse(
            showsWideDetailPane(
                layout = WindowLayout.EXPANDED,
                tab = SelectedTab.LIBRARY,
                detailOpen = false
            )
        )
        // The other roots keep the one-screen-at-a-time route in this slice.
        listOf(SelectedTab.LISTEN, SelectedTab.EXPLORE, SelectedTab.SETTINGS).forEach { tab ->
            assertFalse(
                "tab=$tab",
                showsWideDetailPane(
                    layout = WindowLayout.EXPANDED,
                    tab = tab,
                    detailOpen = true
                )
            )
        }
    }

    @Test
    fun theDetailPaneFollowsTheSameFiveHundredNinetyNineSixHundredBoundary() {
        assertFalse(
            showsWideDetailPane(
                layout = windowLayoutFor(599),
                tab = SelectedTab.LIBRARY,
                detailOpen = true
            )
        )
        assertTrue(
            showsWideDetailPane(
                layout = windowLayoutFor(600),
                tab = SelectedTab.LIBRARY,
                detailOpen = true
            )
        )
    }

    @Test
    fun exploreKeepsTheParentListBesideTheWorkOnlyOnAWideWindow() {
        assertTrue(
            showsWideExploreDetailPane(
                layout = WindowLayout.EXPANDED,
                tab = SelectedTab.EXPLORE,
                hasParentList = true
            )
        )
        assertFalse(
            showsWideExploreDetailPane(
                layout = WindowLayout.COMPACT,
                tab = SelectedTab.EXPLORE,
                hasParentList = true
            )
        )
        // No genre/series under the work (rating, person lists): the phone
        // route stays, deliberately, until the owner answers on #900.
        assertFalse(
            showsWideExploreDetailPane(
                layout = WindowLayout.EXPANDED,
                tab = SelectedTab.EXPLORE,
                hasParentList = false
            )
        )
        // Only «Огляд» owns that parent list.
        listOf(SelectedTab.LISTEN, SelectedTab.LIBRARY, SelectedTab.FRIENDS, SelectedTab.SETTINGS)
            .forEach { tab ->
                assertFalse(
                    "tab=$tab",
                    showsWideExploreDetailPane(
                        layout = WindowLayout.EXPANDED,
                        tab = tab,
                        hasParentList = true
                    )
                )
            }
    }

    @Test
    fun theExplorePaneFollowsTheSameFiveHundredNinetyNineSixHundredBoundary() {
        assertFalse(
            showsWideExploreDetailPane(
                layout = windowLayoutFor(599),
                tab = SelectedTab.EXPLORE,
                hasParentList = true
            )
        )
        assertTrue(
            showsWideExploreDetailPane(
                layout = windowLayoutFor(600),
                tab = SelectedTab.EXPLORE,
                hasParentList = true
            )
        )
    }

    // ---------------------------------------------------------------- #1205

    /**
     * #1205 — the second width line, and the two numbers that matter are 839
     * and 840 (the owner's decision of 2026-10-10 puts the player pane at
     * «від 840 dp»). The pairs below are deliberately both sides of it.
     */
    @Test
    fun eightHundredThirtyNineDpHasNoRoomForThePlayerPane() {
        assertFalse("839 dp is a window the player must still cover", hasRoomForPlayerPane(839))
    }

    @Test
    fun eightHundredFortyDpHasRoomForThePlayerPane() {
        assertTrue("840 dp is where the player gets its own column", hasRoomForPlayerPane(840))
    }

    @Test
    fun thePlayerPaneBoundaryIsTheOnlyThingThatMovesIt() {
        assertFalse(
            "an OPEN player on 839 dp keeps the full-screen surface",
            showsPlayerPane(widthDp = PlayerPaneMinWidthDp - 1, playerOpen = true)
        )
        assertTrue(
            "an OPEN player on 840 dp takes the side pane",
            showsPlayerPane(widthDp = PlayerPaneMinWidthDp, playerOpen = true)
        )
    }

    /**
     * The width alone never draws a pane: 600–839 dp is a wide window, and it
     * still covers the screen when the player is open — but a window with no
     * player open has no pane at ANY width, however wide it is.
     */
    @Test
    fun thereIsNoPlayerPaneWithoutAnOpenPlayer() {
        listOf(600, 839, 840, 1024, 2560).forEach { widthDp ->
            assertFalse(
                "width=$widthDp with nothing open",
                showsPlayerPane(widthDp = widthDp, playerOpen = false)
            )
        }
    }

    /** 600–839 dp is EXPANDED and still gets the FULL-SCREEN player. */
    @Test
    fun theFullScreenPlayerSurvivesEveryWidthBelowTheLine() {
        listOf(0, 320, 411, 599, 600, 720, 839).forEach { widthDp ->
            assertFalse(
                "width=$widthDp must keep the full-screen player",
                showsPlayerPane(widthDp = widthDp, playerOpen = true)
            )
        }
    }

    /**
     * #1205 decision 2 — the mini-player's leading column turns on at the SAME
     * line, and only while the bar is actually there.
     */
    @Test
    fun theLeadingMiniPlayerFollowsTheSameEightThirtyNineEightFortyBoundary() {
        assertFalse(
            showsLeadingMiniPlayer(widthDp = PlayerPaneMinWidthDp - 1, miniPlayerVisible = true)
        )
        assertTrue(
            showsLeadingMiniPlayer(widthDp = PlayerPaneMinWidthDp, miniPlayerVisible = true)
        )
        assertFalse(
            "no bar, no column to widen",
            showsLeadingMiniPlayer(widthDp = 2560, miniPlayerVisible = false)
        )
    }

    /**
     * #1205 decision 3 — there is NO height condition. A landscape phone is
     * 905 × 411 dp (#962 measured it): wide past BOTH width lines, and short.
     * The pane answers by width alone, whatever the height.
     */
    @Test
    fun thePlayerPaneIgnoresHeightOnALandscapePhone() {
        assertTrue(
            "905 × 411 dp is past the 840 dp line",
            showsPlayerPane(widthDp = 905, playerOpen = true)
        )
        assertFalse(
            "800 × 360 dp — the window the owner named — is below it",
            showsPlayerPane(widthDp = 800, playerOpen = true)
        )
        assertFalse(
            showsLeadingMiniPlayer(widthDp = 800, miniPlayerVisible = true)
        )
    }
}
