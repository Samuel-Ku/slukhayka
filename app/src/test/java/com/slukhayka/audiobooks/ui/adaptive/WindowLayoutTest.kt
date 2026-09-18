package com.slukhayka.audiobooks.ui.adaptive

import org.junit.Assert.assertEquals
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
}
