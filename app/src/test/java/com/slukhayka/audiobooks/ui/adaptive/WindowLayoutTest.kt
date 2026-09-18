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
}
