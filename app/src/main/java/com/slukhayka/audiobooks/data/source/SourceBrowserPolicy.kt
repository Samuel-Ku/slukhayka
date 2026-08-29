package com.slukhayka.audiobooks.data.source

/**
 * Spec-42 #427 — the source-scoped browser boundary, pure JVM.
 *
 * Each WebView-pattern source owns an allowlist of host suffixes: the listener
 * must not leave it for a third-party site, whether by tapping a link,
 * typing/pasting into the address field or via a programmatic `loadUrl`.
 *
 * Only `http`/`https` are ever allowed; any other scheme is blocked before the
 * host check (SEC-011). Host comparison is case-insensitive and recognises
 * subdomains: `s1.reasd.org` matches `reasd.org`, `www.4read.org` matches
 * `4read.org`. This is the one place the allowlist lives — callers never
 * hard-code host strings.
 *
 * Cookie isolation is the companion guarantee: callers read the Cookie header
 * just-in-time for the concrete request host (see [SourceCookieProvider]), so
 * a cookie earned on `4read.org` is never copied onto a request to
 * `reasd.org` or to another source.
 */
object SourceBrowserPolicy {

    /**
     * Base hosts that are considered part of the source. `www.` is stripped
     * before comparison so both `https://4read.org/...` and
     * `https://www.4read.org/...` match the same entry. Subdomains are allowed
     * transparently (`s1.reasd.org` → `reasd.org`).
     */
    fun allowedHostsFor(sourceId: String): Set<String> = when (sourceId) {
        "4read" -> setOf("4read.org", "reasd.org")
        "sluhay" -> setOf("sluhay.com")
        "sluhayknigi" -> setOf("sluhayknigi.com")
        else -> emptySet()
    }

    /**
     * Whether [url] is allowed for the in-app browser scoped to [sourceId].
     *
     * - scheme must be exactly `http` or `https` (lowercased);
     * - host must be present and end-with one of the allowed base hosts
     *   (exact or subdomain), case-insensitive, `www.` stripped.
     *
     * Any parse failure, blank URL or unknown source → not allowed.
     */
    fun isUrlAllowed(url: String, sourceId: String): Boolean {
        if (url.isBlank()) return false
        val allowed = allowedHostsFor(sourceId)
        if (allowed.isEmpty()) return false
        val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val rawHost = parsed.host ?: return false
        if (rawHost.isBlank()) return false
        val host = rawHost.lowercase().removePrefix("www.")
        if (host.isBlank()) return false
        return allowed.any { base ->
            val b = base.lowercase().removePrefix("www.")
            host == b || host.endsWith(".$b")
        }
    }

    /**
     * Whether [requestUrl] is allowed to carry the source's cookies. This is
     * strictly per-host: an allowlisted source may own several hosts (e.g.
     * 4read → `{4read.org, reasd.org}`) but a cookie earned on one must never
     * be copied onto the other. Callers should read the Cookie header
     * just-in-time via [SourceCookieProvider.cookieFor] for the exact
     * [requestUrl] host; this helper only answers the allowlist part.
     */
    fun isCookieHostAllowed(requestUrl: String, sourceId: String): Boolean = isUrlAllowed(requestUrl, sourceId)

    /** Normalised host extraction for diagnostics / tests; null when unparseable. */
    fun extractHost(url: String): String? = runCatching {
        java.net.URI(url.trim()).host?.lowercase()?.removePrefix("www.")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
