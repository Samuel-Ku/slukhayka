package com.slukhayka.audiobooks.data.catalog

/**
 * #526 — the sitemap lane's limits. A sitemap is a cheap URL inventory, not a
 * crawl: the document is bounded, the extracted set is bounded, and the index
 * lives a week (a book inventory moves slowly, and one request per source per
 * week is the whole point).
 */
object SitemapPolicy {

    /** The URL inventory may live a week; one request per source per week. */
    const val SITEMAP_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** An oversized document is a miss, never parsed. */
    const val MAX_BYTES: Int = 4 * 1024 * 1024

    /** And one document never contributes more URLs than this. */
    const val MAX_ENTRIES: Int = 20_000
}

/** One sitemap URL: what to fetch, and the canonical identity it dedupes on. */
data class SitemapEntry(
    val url: String,
    val canonicalUrl: String
)

/**
 * #526 — the bounded sitemap reader. It accepts BOTH a `<urlset>` and a
 * sitemap `<sitemapindex>` (both carry `<loc>` children, which is the only
 * shape the lane needs: a child sitemap is followed by the source's own spec,
 * never crawled recursively here). Every loc is canonicalized (lowercased
 * scheme/host, no fragment, no query, no trailing slash) and filtered through
 * the caller's host allowlist; duplicates collapse onto their canonical form.
 *
 * Honour the bounds: an oversized document answers `null` (an honest miss that
 * must never replace the last good index), and a malformed document answers an
 * empty list. Neither ever throws.
 */
object SitemapParser {

    private val LOC = Regex("<loc>\\s*([^<\\s]+)\\s*</loc>", RegexOption.IGNORE_CASE)

    /** Canonicalizes one loc; null when it is not an absolute http(s) URL. */
    fun canonicalUrl(raw: String): String? {
        val withoutFragment = raw.trim().substringBefore('#')
        val withoutQuery = withoutFragment.substringBefore('?')
        val schemeEnd = withoutQuery.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = withoutQuery.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = withoutQuery.substring(schemeEnd + 3)
        if (rest.isBlank()) return null
        val hostEnd = rest.indexOf('/').let { if (it == -1) rest.length else it }
        val host = rest.substring(0, hostEnd).lowercase()
        if (host.isBlank()) return null
        val path = rest.substring(hostEnd).ifEmpty { "/" }
        val normalizedPath = if (path.length > 1) path.trimEnd('/') else path
        return "$scheme://$host$normalizedPath"
    }

    /**
     * Extracts the accepted, canonical, deduplicated locs in document order.
     * Null means the document was oversized (honest miss).
     */
    fun parse(xml: String, accept: (canonicalUrl: String) -> Boolean): List<SitemapEntry>? {
        if (xml.length > SitemapPolicy.MAX_BYTES) return null
        val seen = HashSet<String>()
        val entries = ArrayList<SitemapEntry>()
        for (match in LOC.findAll(xml)) {
            val raw = match.groupValues[1].trim().substringBefore('#')
            val canonical = canonicalUrl(raw) ?: continue
            if (!accept(canonical)) continue
            if (!seen.add(canonical)) continue
            entries += SitemapEntry(url = raw, canonicalUrl = canonical)
            if (entries.size >= SitemapPolicy.MAX_ENTRIES) break
        }
        return entries
    }
}
