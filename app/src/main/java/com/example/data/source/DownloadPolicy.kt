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
 * - `audiobookmp3` — track mp3s on the playerjs CDN (redirectto.cc) answer
 *   200 only with the site Referer (403 without; verified 2026-08-11); robots
 *   open, no download prohibition found → allowed, Referer required in the
 *   download path (see [downloadHeadersFor]).
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
 * Extra HTTP headers the offline downloader must send per source CDN.
 * audiobookmp3's playerjs CDN 403s without the site Referer (verified in T6);
 * the streaming adapters already send it for playlist fetches, and the
 * download path needs the same for track files.
 */
fun downloadHeadersFor(streamUrl: String): Map<String, String> = when {
    streamUrl.contains("redirectto.cc") -> mapOf("Referer" to "https://audiobook-mp3.com/uk")
    else -> emptyMap()
}
