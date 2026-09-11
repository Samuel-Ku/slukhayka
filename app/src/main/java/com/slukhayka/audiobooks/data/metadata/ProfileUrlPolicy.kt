package com.slukhayka.audiobooks.data.metadata

/**
 * ADR-0039 §7 / spec #681 T4 (#685) — the stability rule for chapter URLs
 * written to the shared base: only links without a signature or an expiry may
 * be persisted. Signed CDN links (redirectto.cc), YouTube's expiring
 * googlevideo links and any URL carrying a signature/expiry query parameter
 * are per-session artefacts — a clean cookie-free request from the next
 * listener cannot rely on them, so they never leave the device. A profile
 * with even one unstable chapter is refused as a whole: dropping single
 * chapters would silently shift the chapter index pairing.
 */
object ProfileUrlPolicy {

    private val SIGNED_HOST_SUFFIXES = listOf(
        "googlevideo.com",
        "redirectto.cc"
    )

    private val SIGNATURE_QUERY_KEYS = listOf(
        "expire",
        "expires",
        "token",
        "sig",
        "signature",
        "verify",
        "md5",
        "hash",
        "policy",
        "key-pair-id",
        "awsaccesskeyid"
    )

    /** True when [url] is an http(s) link that does not expire or carry a signature. */
    fun isStableChapterUrl(url: String): Boolean {
        if (!BookProfileLimits.isHttpUrl(url)) return false
        val uri = try {
            java.net.URI(url)
        } catch (_: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (SIGNED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) return false
        val query = uri.rawQuery ?: return true
        return query.split('&').none { pair ->
            val key = pair.substringBefore('=').lowercase()
            key in SIGNATURE_QUERY_KEYS || key.startsWith("x-amz-")
        }
    }

    /**
     * The same profile when every chapter URL is stable; null when any is
     * signed, expiring or malformed — the caller keeps the recovery local.
     */
    fun stableChapters(profile: BookProfile): BookProfile? =
        if (profile.chapters.all { isStableChapterUrl(it.streamUrl) }) profile else null
}
