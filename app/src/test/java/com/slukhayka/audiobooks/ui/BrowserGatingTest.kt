package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec-15 T2 — the debug-gating rules of the in-app browser surfaces, pinned
 * on the pure seam (no Android build variant needed):
 *
 * - the 4read legacy browser is removed from the UI entirely — its "open on
 *   site" action is ALWAYS the system browser, debug included;
 * - a WebView-source surface (sluhay first) is an in-app destination only in
 *   debug builds; release builds open the system browser.
 */
class BrowserGatingTest {

    @Test
    fun `4read open-on-site is always the system browser - legacy browser removed from UI`() {
        // Even a debug build never opens the 4read legacy browser: it is gone
        // from the UI entirely (its seam-tested import doors stay behind the
        // repository for fixtures).
        assertEquals(BrowserDestination.SYSTEM_BROWSER, browserDestinationFor(isDebug = true, sourceId = "4read"))
        assertEquals(BrowserDestination.SYSTEM_BROWSER, browserDestinationFor(isDebug = false, sourceId = "4read"))
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
