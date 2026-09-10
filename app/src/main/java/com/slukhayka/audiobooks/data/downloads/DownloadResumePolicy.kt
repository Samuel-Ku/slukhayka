package com.slukhayka.audiobooks.data.downloads

/**
 * #387 — the pure Range-resume decision for big chapter downloads. A
 * download that dies mid-stream (the 2026-08-27 device case: an ~80 MB
 * YouTube chapter lost at ~10 MB with `Software caused connection abort`)
 * must continue from the kept partial on retry instead of starting from
 * zero — but ONLY when the server proves the resume is sound.
 *
 * The partial lives in a STABLE resume temp (`<chapterId>.<urlKey>.mp3.resume`,
 * deliberately NOT `*.tmp` so the pause/cancel cleanup, which deletes
 * `*.tmp`, keeps its contract untouched). The loop moves it under the
 * run's session temp, appends through a `Range: bytes=<kept>-` request
 * ([HttpFetcher.getRangeStream]), and keeps it again on failure. A resume
 * temp is consumed at chapter start and deleted on every chapterOk path,
 * so leftovers are exactly the resumable set — never garbage.
 *
 * The temp is keyed by the TRACK url (not the resolved stream url): a
 * re-resolved 4read page yields a new track url, so its stale partial is
 * simply never adopted — otherwise a Range slice of NEW content appended
 * to an OLD prefix would still pass the length check (kept + (total-kept)
 * == total) and silently splice two different audios. YouTube watch urls
 * are stable across signed-url refreshes, so the issue's own 80 MB case
 * resumes normally.

 * Soundness rule (the whole point): a 206 resumes ONLY when its
 * `Content-Range: bytes S-E/T` verbatim header says the slice starts
 * exactly where the partial ends (S == kept) — anything else (200 that
 * ignored the Range, a mismatched start, a missing/invalid header, any
 * other status) restarts from zero or fails WITHOUT touching the partial,
 * so a corrupt or stale partial can never poison the download. The final
 * verification stays the existing one: exact total match when the total is
 * known. A zombie tail from a previous generation can at worst append one
 * unchecked chunk — the length check then fails closed and the temp is
 * dropped, exactly like any other verification failure.
 */
object DownloadResumePolicy {

    /** Stable per-chapter partial name. No `.tmp` suffix by design (see above). */
    fun resumeTempName(chapterId: String, trackUrl: String): String =
        "$chapterId.${urlKey(trackUrl)}.mp3.resume"

    /**
     * Short stable identity of the track url for the resume name: the first
     * 16 hex chars of SHA-256. A plain hashCode is not enough — two urls
     * sharing one partial would reintroduce the splice corruption above.
     */
    fun urlKey(trackUrl: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(trackUrl.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
    }

    /** HTTP Partial Content — the only status a resume accepts. */
    const val HTTP_PARTIAL = 206

    /** HTTP OK — the server ignored the Range; the partial is discarded. */
    const val HTTP_OK = 200

    /** Parsed `Content-Range: bytes S-E/T`: the slice start and the total. */
    data class ContentRange(val startBytes: Long, val totalBytes: Long?)

    /**
     * Parses `bytes S-E/T` (T may be `*` = unknown total). Null on anything
     * else — a missing or malformed header never resumes.
     */
    fun parseContentRange(header: String?): ContentRange? {
        if (header.isNullOrBlank()) return null
        val match = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""")
            .matchEntire(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val total = if (match.groupValues[3] == "*") null else match.groupValues[3].toLongOrNull()
        if (total != null && (total <= 0 || end >= total)) return null
        return ContentRange(startBytes = start, totalBytes = total)
    }

    /** What the loop does with the kept partial for this response. */
    sealed interface Decision {
        /** Append the 206 body at [existingBytes]; verify against [totalBytes]. */
        data class Resume(val totalBytes: Long?) : Decision

        /** Discard the partial and fetch from zero (200 or 206 mismatch). */
        data object Restart : Decision

        /** Keep the partial untouched for the next run; chapter stays failed. */
        data object KeepForLater : Decision
    }

    /**
     * The resume decision for a Range request sent from [existingBytes].
     * Pure: status + the server's own Content-Range, nothing else.
     */
    fun decide(existingBytes: Long, status: Int, contentRange: String?): Decision {
        if (existingBytes <= 0) return Decision.Restart
        if (status != HTTP_PARTIAL) return Decision.Restart
        val range = parseContentRange(contentRange) ?: return Decision.Restart
        if (range.startBytes != existingBytes) return Decision.Restart
        return Decision.Resume(totalBytes = range.totalBytes)
    }
}
