package com.example.ui

import com.example.data.catalog.SourceCatalog.SourceNewFeed
import com.example.data.source.SourceBook
import com.example.ui.screens.visibleSourceFeeds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec-15 T2 — the debug-gating rules of the in-app browser surfaces, pinned
 * on the pure seam (no Android build variant needed):
 *
 * - the 4read legacy browser is removed from the UI entirely — its "open on
 *   site" action is ALWAYS the system browser, debug included;
 * - a WebView-source surface (sluhay first) is an in-app destination only in
 *   debug builds; release builds open the system browser;
 * - a session-bound feed row (its stale-session CTA needs the in-app browser
 *   to refresh the challenge) is hidden when no browser surface exists.
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

    private fun feed(sourceId: String, sessionBound: Boolean) = SourceNewFeed(
        sourceId = sourceId,
        sourceName = sourceId,
        books = listOf(
            SourceBook(title = "Книга", author = "Автор", url = "https://$sourceId.example/1", sourceId = sourceId)
        ),
        sessionBound = sessionBound
    )

    @Test
    fun `session-bound feed rows are hidden when no browser surface exists`() {
        val feeds = listOf(feed("sluhay", sessionBound = true), feed("soundbooks", sessionBound = false))

        // Debug build (browser surface present): both rows show.
        assertEquals(listOf("sluhay", "soundbooks"), visibleSourceFeeds(feeds, hasBrowserSurface = true).map { it.sourceId })
        // Release build (no in-app browser): the session-bound row is hidden —
        // its stale-session CTA would be a dead end — the server-fetch row stays.
        assertEquals(listOf("soundbooks"), visibleSourceFeeds(feeds, hasBrowserSurface = false).map { it.sourceId })
    }

    @Test
    fun `server-fetch feeds are never hidden by the browser gate`() {
        val feeds = listOf(feed("soundbooks", sessionBound = false), feed("lihtar", sessionBound = false))
        assertEquals(2, visibleSourceFeeds(feeds, hasBrowserSurface = false).size)
    }
}
