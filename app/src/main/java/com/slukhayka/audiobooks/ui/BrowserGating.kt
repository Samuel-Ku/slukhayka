package com.slukhayka.audiobooks.ui

/**
 * Spec-15 T2 — where «Відкрити на сайті» goes. Pure JVM so the debug-gating
 * test can pin both build flavours without an Android build variant.
 *
 * The 4read legacy browser is removed from the UI entirely (its seam-tested
 * import doors live behind the repository seam for fixtures); its book-page
 * "open on site" action therefore ALWAYS opens the system browser, in debug
 * too. WebView-pattern sources (sluhay.com, sluhayknigi.com — behind
 * Cloudflare) keep an in-app browser surface, and only in debug builds: a
 * release build never opens an in-app WebView.
 */
enum class BrowserDestination { SYSTEM_BROWSER, IN_APP_BROWSER }

/** WebView-pattern sources (spec-13): discovery needs a live browser session. */
private fun isWebViewSource(sourceId: String): Boolean =
    sourceId == "sluhay" || sourceId == "sluhayknigi"

/**
 * The destination of a book's "open on site" action for [sourceId]. 4read is
 * always the system browser (its legacy browser is removed from the UI);
 * WebView-pattern sources keep an in-app browser surface only in debug builds;
 * every other source has no in-app browser in any build.
 */
fun browserDestinationFor(isDebug: Boolean, sourceId: String): BrowserDestination = when {
    sourceId == "4read" -> BrowserDestination.SYSTEM_BROWSER
    isDebug && isWebViewSource(sourceId) -> BrowserDestination.IN_APP_BROWSER
    else -> BrowserDestination.SYSTEM_BROWSER
}
