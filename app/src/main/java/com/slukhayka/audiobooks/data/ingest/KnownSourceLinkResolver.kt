package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.source.UNKNOWN_SOURCE_ID
import java.net.URI

/**
 * #1487 (slice 1) — a pasted link to a BOOK PAGE of a known source.
 *
 * The listener's ask: «send a link to a book and it opens it and pulls the
 * media data out». YouTube and Telegram already have their own doors; a
 * catalogue page had none. This resolver closes the first half — the host is
 * routed through the EXISTING registry (ADR-0038: `SourceRegistry.entries`,
 * home host plus transport hosts, read by `sourceIdForUrl`), and the page is
 * then imported by the ordinary source door. No second host table: a source
 * that registers a host here is a source the import can already fetch.
 *
 * Pure: normalisation and one [sourceFor] read — no network, no Android.
 *
 * Boundaries, named:
 * - only hosts the caller's [sourceFor] claims; an unknown host returns null
 *   (the caller keeps its honest `Unsupported`), never a guess;
 * - the HOME page of a source is not a book page and is refused: importing
 *   without a page would invent a book;
 * - whether the path is really a book page is decided by the import itself —
 *   a tag/genre page fails there as `IMPORT_FAILED`, an honest refusal.
 */
data class KnownSourceLink(val sourceId: String, val url: String)

object KnownSourceLinkResolver {

    /**
     * The canonical [KnownSourceLink] for [raw], or null when the link is not
     * a page of a registered source. [sourceFor] is the registry read
     * (`sourceIdForUrl` in production); it returns [UNKNOWN_SOURCE_ID] for a
     * host no source claims.
     */
    fun resolve(raw: String, sourceFor: (String) -> String): KnownSourceLink? {
        val url = canonicalUrl(raw) ?: return null
        val sourceId = sourceFor(url)
        if (sourceId.isBlank() || sourceId == UNKNOWN_SOURCE_ID) return null
        return KnownSourceLink(sourceId = sourceId, url = url)
    }

    /**
     * `https://<host without www><path>`: scheme forced to https, host
     * lowercased, query/fragment dropped (tracking parameters do not change
     * the book), trailing slash removed. Path case is kept — pages are
     * case-sensitive. Null for non-http(s) links and for a bare host root.
     */
    fun canonicalUrl(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host.isBlank()) return null
        val path = uri.rawPath?.trimEnd('/') ?: return null
        if (path.isBlank()) return null
        return "https://$host$path"
    }
}
