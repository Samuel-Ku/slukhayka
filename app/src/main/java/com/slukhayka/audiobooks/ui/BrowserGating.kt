package com.slukhayka.audiobooks.ui

/**
 * Spec-15 T2 — where «Відкрити на сайті» goes. Pure JVM so the debug-gating
 * test can pin both build flavours without an Android build variant.
 *
 * 4read is a browser-backed source: its Cloudflare session and playlist
 * cookies must stay inside the app's recovery surface in every build. Other
 * WebView-pattern sources remain debug-only until their release contract is
 * explicitly adopted.
 */
enum class BrowserDestination { SYSTEM_BROWSER, IN_APP_BROWSER }

/** WebView-pattern sources (spec-13): discovery needs a live browser session. */
private fun isWebViewSource(sourceId: String): Boolean =
    sourceId == "4read" || sourceId == "sluhay" || sourceId == "sluhayknigi"

/**
 * The destination of a book's "open on site" action for [sourceId]. 4read
 * always uses the in-app browser because it is also the only supported
 * recovery path for its session-bound audio. Other WebView sources are kept
 * behind the existing debug gate; ordinary direct sources use the system
 * browser.
 */
fun browserDestinationFor(isDebug: Boolean, sourceId: String): BrowserDestination = when {
    sourceId == "4read" -> BrowserDestination.IN_APP_BROWSER
    isDebug && isWebViewSource(sourceId) -> BrowserDestination.IN_APP_BROWSER
    else -> BrowserDestination.SYSTEM_BROWSER
}
