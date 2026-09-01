package com.slukhayka.audiobooks.data.source

/**
 * Spec-42 #427 — one shared host-aware Cookie provider, pure interface.
 *
 * The provider is *host-aware*: it reads the Cookie header just-in-time for
 * the concrete request host via [cookieFor]. A cookie earned on `4read.org`
 * is never copied onto a request to `reasd.org` or to another source's host
 * — the isolation is by host, even when both hosts belong to the same source.
 *
 * Production wires the Android WebView jar ([AndroidSourceCookieProvider]);
 * JVM/tests wire a fake that returns configured per-host cookies. There is
 * exactly one shared instance per process (created in [com.slukhayka.audiobooks.App]),
 * never a per-adapter lambda, so the allowlist + host-awareness cannot drift.
 */
interface SourceCookieProvider {
    /**
     * Cookie header value for [url]'s host, read just-in-time from the
     * listener's WebView jar. Empty when no cookie exists, when [url] is not
     * http(s), or when the host is unparseable. Never returns a cookie that
     * belongs to a different host — host-awareness is the isolation guarantee.
     */
    fun cookieFor(url: String): String
}

/**
 * The production host-aware provider: reads just-in-time from the WebView's
 * single process-wide [android.webkit.CookieManager] for the exact [url] host.
 * Third-party cookies remain disabled at the WebView level
 * (`setAcceptThirdPartyCookies(false)`), so the jar itself never mixes hosts.
 */
object AndroidSourceCookieProvider : SourceCookieProvider {
    override fun cookieFor(url: String): String {
        if (url.isBlank()) return ""
        val scheme = runCatching { java.net.URI(url.trim()).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") return ""
        return runCatching {
            android.webkit.CookieManager.getInstance().getCookie(url.trim())
        }.getOrNull().orEmpty()
    }
}

/**
 * JVM / test fake: returns a configured cookie string per normalised host.
 * Host comparison is case-insensitive and strips `www.` like the production
 * browser surface.
 *
 * Example: `FakeSourceCookieProvider(mapOf("4read.org" to "cf_clearance=abc", "sluhay.com" to "cf_clearance=sluhay"))`
 * returns `"cf_clearance=abc"` for any URL whose host is `4read.org` or
 * `*.4read.org` alias in the map's keys, and empty otherwise — never a cross-host leak.
 */
class FakeSourceCookieProvider(
    private val cookiesByHost: Map<String, String> = emptyMap()
) : SourceCookieProvider {

    // Normalised host → cookie; `www.` prefix already stripped.
    private val normalized = cookiesByHost.entries.associate { (host, cookie) ->
        host.lowercase().removePrefix("www.") to cookie
    }

    override fun cookieFor(url: String): String {
        if (url.isBlank()) return ""
        val scheme = runCatching { java.net.URI(url.trim()).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") return ""
        val host = runCatching { java.net.URI(url.trim()).host?.lowercase()?.removePrefix("www.") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return ""
        // Direct match first, then suffix match for configured base hosts
        // is not needed here: tests configure exact hosts; subdomain isolation
        // is modelled by expecting empty for a subdomain not in the map.
        // To faithfully model production's jar (where `example.com` cookies are
        // visible for `sub.example.com` only when the cookie's Domain attribute
        // allows it), we intentionally return empty for subdomains unless the
        // test explicitly configured them — strict isolation by default.
        return normalized[host].orEmpty()
    }
}

/**
 * Convenience: the Cookie header map for [url] when a non-empty cookie exists,
 * otherwise empty. Callers merge this map into the request's extra headers;
 * never inject an empty `Cookie: ` header.
 */
fun SourceCookieProvider.cookieHeadersFor(url: String): Map<String, String> {
    val cookie = cookieFor(url).trim()
    return if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie)
}

/**
 * Browser-shaped headers required by 4read's protected cover endpoint.
 *
 * Kept beside the source transport policy: callers receive no special headers
 * for another source or for an audio host, and a 4read cookie is read only for
 * the exact 4read cover host.
 */
fun SourceCookieProvider.coverHeadersFor(url: String): Map<String, String> {
    val host = runCatching { java.net.URI(url.trim()).host?.lowercase()?.removePrefix("www.") }
        .getOrNull()
    if (host != "4read.org") return emptyMap()
    return buildMap {
        put("Referer", "https://4read.org/")
        put("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        put("Sec-Fetch-Dest", "image")
        put("Sec-Fetch-Mode", "no-cors")
        put("Sec-Fetch-Site", "same-origin")
        cookieFor(url).trim().takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }
}
