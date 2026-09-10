package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.source.BrowserRecoveryProfiles
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy

/**
 * Spec-15 T2 / Spec-42 #425 — where «Відкрити на сайті» goes. Pure JVM so the
 * debug-gating test can pin both build flavours without an Android build variant.
 *
 * 4read is a WebView-pattern source in release builds (spec-42 #425): the
 * browser is reachable only for 4read and is source-scoped. The 4read legacy
 * browser was removed from the UI entirely, but the v1.3.7 recovery brings it
 * back as a release-accessible WebView surface. WebView-pattern sources
 * (sluhay.com, sluhayknigi.com — behind Cloudflare) keep an in-app browser
 * surface only in debug builds.
 */
enum class BrowserDestination { SYSTEM_BROWSER, IN_APP_BROWSER }

/**
 * WebView-pattern sources (spec-13): discovery needs a live browser session.
 * ADR-0036 (spec-48 T1): «source» means a declared Browser Recovery Profile —
 * the knowledge lives beside each adapter, not in a string comparison here.
 */
private fun isProfileSource(sourceId: String): Boolean =
    BrowserRecoveryProfiles.hasSource(sourceId)

/**
 * The destination of a book's "open on site" action for [sourceId]. A source
 * whose profile declares [BrowserRecoveryProfile.releaseBrowserDoor] is always
 * the in-app browser (release-accessible recovery — 4read today); sources
 * with a profile keep an in-app browser surface only in debug builds; every
 * other source has no in-app browser in any build.
 */
fun browserDestinationFor(isDebug: Boolean, sourceId: String): BrowserDestination = when {
    BrowserRecoveryProfiles.forSource(sourceId).releaseBrowserDoor -> BrowserDestination.IN_APP_BROWSER
    isDebug && isProfileSource(sourceId) -> BrowserDestination.IN_APP_BROWSER
    else -> BrowserDestination.SYSTEM_BROWSER
}
