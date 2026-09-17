package com.slukhayka.audiobooks.data.metadata

/**
 * Moderation T3 (#836) — the rejection BLOCKLIST entry. Written only by the
 * curator's bot (Admin SDK); the app reads it to refuse honestly BEFORE
 * queueing, so a rejected link can never come back into the queue. The key is
 * the same `sha256(canonicalUrl)` the candidate queue uses, which is what makes
 * "the same link in different shapes" one entry.
 */
data class RejectedSubmission(
    val canonicalUrl: String,
    val reason: String = "",
    val rejectedAt: Long = 0L,
    val rejectedBy: String = ""
) {
    val documentId: String get() = RejectedSubmissionCodec.documentId(canonicalUrl)
}

object RejectedSubmissionCodec {

    const val COLLECTION = "rejected_submissions"
    private const val MAX_TEXT_LEN = 300
    private const val MAX_URL_LEN = 2_000

    /** The SAME key as the candidate queue: `sha256(canonicalUrl)`. */
    fun documentId(canonicalUrl: String): String =
        SubmissionCandidateCodec.documentId(canonicalUrl)

    fun encode(entry: RejectedSubmission): Map<String, Any?> = buildMap {
        put("canonicalUrl", entry.canonicalUrl.trim().take(MAX_URL_LEN))
        put("reason", entry.reason.trim().take(MAX_TEXT_LEN))
        put("rejectedAt", entry.rejectedAt)
        put("rejectedBy", entry.rejectedBy.trim().take(MAX_TEXT_LEN))
    }

    /** @return null when the document is not a usable blocklist entry. */
    fun decode(document: Map<String, Any?>?): RejectedSubmission? {
        if (document == null) return null
        val canonicalUrl = (document["canonicalUrl"] as? String)?.trim().orEmpty()
        if (canonicalUrl.isEmpty()) return null
        return RejectedSubmission(
            canonicalUrl = canonicalUrl,
            reason = (document["reason"] as? String).orEmpty(),
            rejectedAt = (document["rejectedAt"] as? Number)?.toLong() ?: 0L,
            rejectedBy = (document["rejectedBy"] as? String).orEmpty()
        )
    }

    /** The honest listener-facing verdict for a rejected link. */
    const val LISTENER_VERDICT = "rejected"
}
