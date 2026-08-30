package com.slukhayka.audiobooks.ui

import java.net.URI

/**
 * The one navigation boundary for an in-app Source browser.  A browser
 * session is scoped to its Source: it may load only that Source's page hosts.
 * Audio hosts are deliberately a separate allowlist because they are observed
 * as subresources, never opened as top-level pages.
 */
object SourceBrowserPolicy {
    fun allowsPageNavigation(sourceId: String, url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        return allowsPageHost(sourceId, uri.host)
    }

    fun allowsPageHost(sourceId: String, host: String?): Boolean = when (sourceId) {
        "4read" -> belongsTo(host, "4read.org")
        "sluhay" -> belongsTo(host, "sluhay.com")
        "sluhayknigi" -> belongsTo(host, "sluhayknigi.com")
        else -> false
    }

    fun allowsAudioHost(sourceId: String, host: String?, pageHost: String?): Boolean = when (sourceId) {
        "4read" -> belongsTo(host, "4read.org") || belongsTo(host, "reasd.org")
        "sluhay", "sluhayknigi" -> belongsTo(host, "redirectto.cc") || belongsTo(host, pageHost)
        else -> belongsTo(host, pageHost)
    }

    private fun belongsTo(host: String?, domain: String?): Boolean {
        val normalizedHost = host?.lowercase()?.removePrefix("www.") ?: return false
        val normalizedDomain = domain?.lowercase()?.removePrefix("www.") ?: return false
        return normalizedHost == normalizedDomain || normalizedHost.endsWith(".$normalizedDomain")
    }
}
