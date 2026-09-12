package com.slukhayka.audiobooks.data.source

/**
 * #527 — whether a per-source site Referer may travel to [url]. An EMPTY
 * [refererHosts] is the legacy "any host" rule (an adapter that never leaves
 * its own domain); a non-empty set is an explicit allowlist matched on the
 * exact host or a subdomain, so a site Referer can never leak to an unrelated
 * third party. A URL without a parseable host never receives it.
 */
fun refererAllowedFor(url: String, refererHosts: Set<String>): Boolean {
    if (refererHosts.isEmpty()) return true
    val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    if (host.isBlank()) return false
    return refererHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
}
