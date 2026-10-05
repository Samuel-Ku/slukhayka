package com.slukhayka.audiobooks.data.source

import android.webkit.CookieManager

/**
 * The one WebView cookie jar of the process, resolved once instead of per call
 * (#948).
 *
 * `CookieManager.getInstance()` is a stable process-wide singleton in the app,
 * so every reader and writer observes the very same jar: the host-aware
 * [AndroidSourceCookieProvider] (which the composition root wires for the 4read
 * cover session), the source-scoped clearing of `SourceWebViewSession`, and the
 * browser screen's third-party policy and its flush. Resolving per call is the
 * fragile shape — Robolectric's `ShadowCookieManager` keeps its store behind a
 * static that `@Resetter` nulls, so a later `getInstance()` builds a DIFFERENT,
 * empty `RoboCookieManager` and a write and the following read silently land in
 * two stores. That is what made `SourceWebViewSessionIntegrationTest` fail on
 * PRs that never touch cookies.
 *
 * Pinning therefore changes nothing in the app: it is the same singleton, only
 * remembered. The single exception is [unpin], which exists for tests — see
 * there why a pin must not outlive Robolectric's shadow.
 */
internal object AndroidCookieJar {

    @Volatile
    private var pinned: CookieManager? = null

    /** The pinned jar, resolved on first use and remembered from then on. */
    fun instance(): CookieManager = pinned ?: synchronized(this) {
        pinned ?: CookieManager.getInstance().also { pinned = it }
    }

    /**
     * #948 — drop the pin so the next resolution rebinds to the jar of the
     * moment. Test-only: Robolectric rebuilds its shadow between test methods,
     * and a pin that outlived the shadow would hand the next test an empty jar.
     */
    internal fun unpin() {
        synchronized(this) { pinned = null }
    }
}
