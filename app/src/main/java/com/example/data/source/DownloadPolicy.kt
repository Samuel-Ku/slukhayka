package com.example.data.source

/**
 * Spec-10 T6 — the download policy per source, derived from the T1 spike
 * verdicts and the T6 live verification (real HTTP probes, not hearsay).
 *
 * [streamOnlyFor] gates the download action: stream-only sources may stream
 * but never download (their chapters' files are not copied offline).
 *
 * Verdicts:
 * - `soundbooks` — the site documents user-facing downloads («Як завантажити
 *   аудіокнигу»); direct mp3 on the arch CDN serves plain GETs → allowed.
 * - `audiobookmp3`, `sluhay`, `sluhayknigi` — track mp3s on the playerjs CDN
 *   (`*.redirectto.cc`) answer 200 only with the owning site's Referer (403
 *   without; verified live 2026-08-11 for audiobookmp3 and 2026-08-12 for
 *   sluhay/sluhayknigi in the spec-13 T1 spike); robots open, no download
 *   prohibition found → allowed, Referer required in the download path (see
 *   [headersFor]).
 * - `lihtar` — the ToS carries a blanket no-reproduction clause
 *   («будь-яке відтворення, розповсюдження або інше використання матеріалів
 *   без нашої письмової згоди заборонене») and no download allowance →
 *   **stream-only**.
 * - `4read` and anything unknown (legacy books, local imports) keep the
 *   existing behaviour — allowed.
 */
fun streamOnlyFor(sourceId: String): Boolean = when (sourceId) {
    "lihtar" -> true
    else -> false
}

/**
 * Extra HTTP headers the streaming/download paths must send per source
 * (spec-13 T2). The three WebView/server-fetch playerjs sources all stream
 * from the SAME CDN host (`*.redirectto.cc`), so a URL-based heuristic cannot
 * tell them apart — the Referer is owned by the source, not the host:
 *
 * - `sluhay`      → `Referer: https://sluhay.com/`
 * - `sluhayknigi` → `Referer: https://sluhayknigi.com/`
 * - `audiobookmp3`→ `Referer: https://audiobook-mp3.com/uk`
 * - everything else → no extra headers (4read/arch/sound-books serve plain
 *   GETs; SEC-004: never leak a Referer onto hosts that don't need one)
 *
 * [streamUrl] is kept in the signature because the policy may later want to
 * scope a Referer to specific CDN paths; today it is only a documentation
 * hook. Callers derive [sourceId] from the book's primary source URL via the
 * pure [sourceIdForUrl] function in this package.
 */
fun headersFor(sourceId: String, streamUrl: String): Map<String, String> = when (sourceId) {
    "sluhay" -> mapOf("Referer" to "https://sluhay.com/")
    "sluhayknigi" -> mapOf("Referer" to "https://sluhayknigi.com/")
    "audiobookmp3" -> mapOf("Referer" to "https://audiobook-mp3.com/uk")
    else -> emptyMap()
}
