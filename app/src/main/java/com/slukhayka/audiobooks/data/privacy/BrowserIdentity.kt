package com.slukhayka.audiobooks.data.privacy

/**
 * Spec-38 T1 (#253) — the process-wide browser identity. The transport sends
 * the SAME User-Agent the system WebView of this device sends, so a source
 * cannot tell the app's fetches from ordinary mobile browsing (the identical
 * hardcoded UA of every listener was itself a «стая Слухайки» fingerprint).
 *
 * The real UA is read once per process and reported here from App startup
 * (thin glue); until then — and in JVM environments where WebView does not
 * exist — the static fallback answers. The fallback carries NO device model
 * and NO app name: both were leaks (SEC-018 precedent in the player).
 */
object BrowserIdentity {

    /**
     * 4read serves a hard block page to Android WebViews that advertise the
     * embedded-browser markers (`Version/4.0` and `; wv`). Chrome on the same
     * device is accepted. Keep the real engine/device/browser version while
     * removing only those transport-neutral markers from the interactive
     * source browser; the shared HTTP transport continues to use the genuine
     * system WebView identity below.
     */
    fun embeddedBrowserUserAgent(systemUa: String): String = systemUa
        .replace("; wv", "", ignoreCase = true)
        .replace("Version/4.0 ", "", ignoreCase = true)
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    /**
     * Chrome's own reduced-Android shape («K» = generic device). Ordinary,
     * unremarkable, shared with millions of real browsers.
     */
    const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Standard browser defaults the transport sends alongside the UA. */
    const val ACCEPT_HEADER =
        "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,*/*;q=0.8"
    const val ACCEPT_LANGUAGE_HEADER = "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"

    @Volatile
    private var systemUserAgent: String? = null

    /** Called once per process from the composition root; blank reports lose. */
    fun reportSystemUserAgent(ua: String?) {
        if (!ua.isNullOrBlank()) systemUserAgent = ua
    }

    /** The UA every transport request carries. */
    fun currentUserAgent(): String = systemUserAgent ?: FALLBACK_USER_AGENT

    /** Test-only reset — production code never calls this. */
    fun resetForTest() {
        systemUserAgent = null
    }
}
