package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec-15 T2 — the debug-gating rules of the in-app browser surfaces, pinned
 * on the pure seam (no Android build variant needed):
 *
 * - 4read uses the in-app browser in every build because its session is part
 *   of the supported playback/recovery path;
 * - a WebView-source surface (sluhay first) is an in-app destination only in
 *   debug builds; release builds open the system browser.
 */
class BrowserGatingTest {

    @Test
    fun `4read uses the in-app browser in every build`() {
        assertEquals(BrowserDestination.IN_APP_BROWSER, browserDestinationFor(isDebug = true, sourceId = "4read"))
        assertEquals(BrowserDestination.IN_APP_BROWSER, browserDestinationFor(isDebug = false, sourceId = "4read"))
    }

    @Test
    fun `webview-source surface is in-app only in debug builds`() {
        assertEquals(BrowserDestination.IN_APP_BROWSER, browserDestinationFor(isDebug = true, sourceId = "sluhay"))
        // Release build: «Відкрити на сайті» opens the system browser, never
        // an in-app WebView.
        assertEquals(BrowserDestination.SYSTEM_BROWSER, browserDestinationFor(isDebug = false, sourceId = "sluhay"))
        assertEquals(BrowserDestination.SYSTEM_BROWSER, browserDestinationFor(isDebug = false, sourceId = "sluhayknigi"))
    }

    @Test
    fun `unknown sources have no in-app browser in release`() {
        assertEquals(BrowserDestination.SYSTEM_BROWSER, browserDestinationFor(isDebug = false, sourceId = "soundbooks"))
        // A debug build of a source WITHOUT a browser surface (server-fetch
        // sources) must still not invent one.
        assertEquals(BrowserDestination.SYSTEM_BROWSER, browserDestinationFor(isDebug = true, sourceId = "soundbooks"))
    }
}
