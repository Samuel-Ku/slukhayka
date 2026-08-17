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