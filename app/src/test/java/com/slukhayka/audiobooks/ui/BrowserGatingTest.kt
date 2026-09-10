package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.source.BrowserRecoveryProfiles
import com.slukhayka.audiobooks.data.source.SourceBrowserPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-15 T2 / Spec-42 #425 — the debug-gating rules of the in-app browser surfaces, pinned
 * on the pure seam (no Android build variant needed):
 *
 * - 4read is a WebView-pattern source in release builds (spec-42 #425) — its
 *   "open on site" / recovery action is ALWAYS the in-app browser;
 * - a WebView-source surface (sluhay first) is an in-app destination only in
 *   debug builds; release builds open the system browser.
 * - Spec-48 T3 — the gating reads the Browser Recovery Profiles: every
 *   profiled source is gated identically (in-app in debug, system in
 *   release), and 4read's release door comes from its profile flag alone.
 */
class BrowserGatingTest {

    @Test
    fun `4read open-on-site is always the in-app browser - release-accessible recovery`() {
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
    fun `all three profiled browser sources gate identically to sluhay`() {
        // Spec-48 T3 — ukrainianaudiobooks joins the profile registry (the
        // first new consumer of the shared engine); its gating must be
        // identical to the established WebView-pattern pair:
        assertEquals(BrowserDestination.IN_APP_BROWSER, browserDestinationFor(isDebug = true, sourceId = "ukrainianaudiobooks"))
        assertEquals(BrowserDestination.SYSTEM_BROWSER, browserDestinationFor(isDebug = false, sourceId = "ukrainianaudiobooks"))
        // And the sluhay pair keeps its shape through the profile path.
        assertEquals(BrowserDestination.IN_APP_BROWSER, browserDestinationFor(isDebug = true, sourceId = "sluhayknigi"))
    }

    @Test
    fun `only 4read's profile declares the release browser door`() {
        // ADR-0027 — the release door is a per-source decided privilege, a
        // profile flag that is true for 4read only. Connecting a source is
        // data; enabling a release door is a recorded decision.
        assertTrue(BrowserRecoveryProfiles.forSource("4read").releaseBrowserDoor)
        assertFalse(BrowserRecoveryProfiles.forSource("sluhay").releaseBrowserDoor)
        assertFalse(BrowserRecoveryProfiles.forSource("sluhayknigi").releaseBrowserDoor)
        assertFalse(BrowserRecoveryProfiles.forSource("ukrainianaudiobooks").releaseBrowserDoor)
    }

    @Test
    fun `profile registry carries the declared hosts and no invented doors`() {
        // Spec-48 T3 — the ukrainianaudiobooks profile per the spec-47 T1
        // spike verdict: one page host, no search door, no home (recovery
        // falls back to the stored book URL — no URL is ever invented).
        val profile = BrowserRecoveryProfiles.forSource("ukrainianaudiobooks")
        assertEquals(setOf("ukrainianaudiobooks.com"), profile.pageHosts)
        assertEquals(setOf("ukrainianaudiobooks.com"), profile.audioHosts)
        assertNull(profile.searchDoor)
        assertNull(profile.homeUrl)
        assertEquals("ukrainianaudiobooks.com", SourceBrowserPolicy.allowedHostsFor("ukrainianaudiobooks").single())
        assertEquals("sluhayknigi", SourceBrowserPolicy.browserSourceIds.last())
        // The allowlist boundary admits the host (and subdomains), nobody else.
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://ukrainianaudiobooks.com/book/abc", "ukrainianaudiobooks"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://cdn.ukrainianaudiobooks.com/x.mp3", "ukrainianaudiobooks"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://evil.com/", "ukrainianaudiobooks"))
    }

    @Test
    fun `unknown sources have no in-app browser in release`() {
        assertEquals(BrowserDestination.SYSTEM_BROWSER, browserDestinationFor(isDebug = false, sourceId = "soundbooks"))
        // A debug build of a source WITHOUT a browser surface (server-fetch
        // sources) must still not invent one.
        assertEquals(BrowserDestination.SYSTEM_BROWSER, browserDestinationFor(isDebug = true, sourceId = "soundbooks"))
        // The new server-fetch source of spec-47 must not grow a surface
        // from its registration alone.
        assertEquals(BrowserDestination.SYSTEM_BROWSER, browserDestinationFor(isDebug = true, sourceId = "audiobookcoua"))
    }
}
