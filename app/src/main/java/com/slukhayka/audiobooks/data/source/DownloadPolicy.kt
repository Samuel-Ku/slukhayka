package com.slukhayka.audiobooks.data.source

/**
 * Spec-10 T6 — the download policy per source, derived from the T1 spike
 * verdicts and the T6 live verification (real HTTP probes, not hearsay).
 *
 * ADR-0038 — the verdict is the [SourceRegistry] fact (`streamOnly`), and
 * the per-source Referer rules are its scoped `referer` facts. The download
 * gate ([streamOnlyFor]) and the transport headers ([headersFor]) read the
 * registry, never a second hand-kept list.
 */
fun streamOnlyFor(sourceId: String): Boolean = SourceRegistry.streamOnlyFor(sourceId)

/**
 * Extra HTTP headers the streaming/download paths must send per source
 * (spec-13 T2). ADR-0038 — the registry's scoped `referer` rule applies:
 * an unscoped rule (sluhay/sluhayknigi/audiobookmp3) rides every stream host;
 * a scoped one (4read: its own `s*.reasd.org` audio hosts; soundbooks: the
 * archive host) only its hosts. Everything else gets no header (SEC-004:
 * never leak a Referer onto a host that does not need one).
 */
fun headersFor(sourceId: String, streamUrl: String): Map<String, String> =
    SourceRegistry.refererHeaderFor(sourceId, streamUrl)

/**
 * Adds a locally held browser cookie only to 4read/reasd requests. Cookies
 * never enter Room, logs, shared profiles or requests to another host.
 */
fun headersFor(sourceId: String, streamUrl: String, cookieHeader: String?): Map<String, String> =
    headersFor(sourceId, streamUrl).toMutableMap().apply {
        if (sourceId == "4read" && isFourReadAudioHost(streamUrl) && !cookieHeader.isNullOrBlank()) {
            put("Cookie", cookieHeader)
        }
    }

private fun isFourReadAudioHost(streamUrl: String): Boolean {
    val host = hostOf(streamUrl) ?: return false
    return host == "4read.org" || host.endsWith(".4read.org") ||
        host == "reasd.org" || host.endsWith(".reasd.org")
}

private fun hostOf(streamUrl: String): String? = try {
    java.net.URI(streamUrl).host?.lowercase()
} catch (_: Exception) {
    null
}
