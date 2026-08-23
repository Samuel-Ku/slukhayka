package com.slukhayka.audiobooks.data.source

import java.util.Locale

/*
 * Spec-35 T1 — one shared set of pure-JVM parse helpers for the non-4read
 * [SourceAdapter]s (sluhayua, soundbooks, audiobookmp3, lihtar, sluhay).
 *
 * These helpers were duplicated across the adapters (each with its own private
 * copy); they live here so the copies cannot drift. All are pure: no network,
 * no Android — input string in, value out. 4read keeps its own parsing
 * (WebViewHtmlParser) — it is the gold standard and is not part of this seam.
 */

/**
 * Value of the `<meta property="…" content="…">` tag, property-first or
 * content-first. Absent tag/property → null. The regex does not require the
 * tag to close right after `content` (no `\s*` `/?>` requirement), so it also matches
 * unclosed tags or trailing attributes — the deliberate common denominator
 * of the five adapters' former copies.
 */
fun ogMeta(html: String, property: String): String? =
    Regex("""<meta\s+property="$property"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1)
        ?: Regex("""<meta\s+content="([^"]+)"\s+property="$property"""", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1)

/** The entity set the non-4read pages actually use: `&#039;`, `&#39;`, `&quot;`, `&amp;`. */
fun decodeEntities(input: String): String = input
    .replace("&#039;", "'")
    .replace("&#39;", "'")
    .replace("&quot;", "\"")
    .replace("&amp;", "&")

/**
 * Transliterated-slug → display title: hyphens to spaces, trimmed, first
 * letter titlecased (already-uppercase letters — e.g. Cyrillic — untouched).
 */
fun titleFromSlug(slug: String): String =
    slug.replace("-", " ")
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

/**
 * «hh:mm:ss» (or «mm:ss») → seconds; any non-numeric segment, wrong part
 * count, or blank input → null. Mirrors the gold-standard 4read formats
 * (`10:57:18` / `53:42`).
 */
fun parseDurationSeconds(input: String): Long? {
    val parts = input.trim().split(":")
    if (parts.size !in 2..3) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    return when (parts.size) {
        3 -> numbers[0] * 3600L + numbers[1] * 60L + numbers[2]
        else -> numbers[0] * 60L + numbers[1]
    }
}

/** The input with every `<tag>` replaced by [replacement] (default: removed). */
fun stripTags(input: String, replacement: String = ""): String =
    Regex("""<[^>]+>""").replace(input, replacement)

/**
 * #268 — [text] cut before the EARLIEST occurrence of any of [markers]
 * (case-insensitive), trimmed; null when no marker occurs. Cutting by marker
 * list order instead of by position is what once left «Телеграм канал автора
 * t.me/…» inside a 4read blurb (#267): the promo paragraph held a later
 * marker earlier in the list. The earliest POSITION is the only honest cut.
 */
fun cutAtEarliestMarker(text: String, markers: Collection<String>): String? {
    val earliest = markers.fold(-1) { acc, marker ->
        val idx = text.indexOf(marker, ignoreCase = true)
        if (idx >= 0 && (acc < 0 || idx < acc)) idx else acc
    }
    return if (earliest >= 0) text.substring(0, earliest).trim() else null

}

/**
 * #268 — the body interval (`bodyStart` inclusive, `close` exclusive) of the
 * page's `<div … itemprop="description">` annotation container: the opening
 * tag's MATCHING `</div>`, found with a depth count so nested divs (the live
 * DLE container nests a quote div) cannot truncate it, and content AFTER the
 * container (user comments, series lists) can never leak in (#267). Null
 * when absent or unbalanced — callers fall back to their og:description path.
 */
fun itempropDescriptionContainer(html: String): Pair<Int, Int>? {
    val open = DIV_ITEMPROP_OPEN.find(html) ?: return null
    val bodyStart = open.range.last + 1
    var depth = 1
    for (boundary in DIV_BOUNDARY.findAll(html, bodyStart)) {
        if (boundary.value[1] == '/') {
            depth--
            if (depth == 0) return bodyStart to boundary.range.first
        } else {
            depth++
        }
    }
    return null
}

private val DIV_ITEMPROP_OPEN =
    Regex("""<div\b[^>]*itemprop="description"[^>]*>""", RegexOption.IGNORE_CASE)
private val DIV_BOUNDARY = Regex("""<div\b|</div""", RegexOption.IGNORE_CASE)