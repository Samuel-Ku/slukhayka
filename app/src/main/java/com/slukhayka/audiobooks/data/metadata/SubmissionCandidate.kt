package com.slukhayka.audiobooks.data.metadata

import java.security.MessageDigest

/**
 * Moderation T1 (#834) — the CANDIDATE a listener's verified submission writes
 * to `pending_submissions`. It is not a catalogue card: the curator's bot
 * decides, and only the bot ever writes `catalog_cards` (Admin SDK).
 *
 * The document id is `sha256(canonicalUrl)`, so the same link can never queue
 * twice, and [submitterHash] is a hash of the listener's uid — exactly like
 * the curator collections, the raw uid never reaches a shared document.
 */
data class SubmissionCandidate(
    val url: String,
    val canonicalUrl: String,
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val coverUrl: String? = null,
    val uploader: String? = null,
    val durationSeconds: Long? = null,
    val chaptersCount: Int = 0,
    val sourceId: String = "",
    val metadataJson: String? = null,
    val submitterHash: String,
    /** The real-playback verdict moment — the anti-spam gate (ADR-0035). */
    val playedAt: Long,
    val createdAt: Long,
    val state: State = State.PENDING,
    val decidedAt: Long? = null,
    val decidedBy: String? = null
) {
    /** Only the curator's bot moves a candidate out of `pending`. */
    enum class State { PENDING, APPROVED, REJECTED }

    /** The pending_submissions document id: the canonical URL's hash. */
    val documentId: String get() = SubmissionCandidateCodec.documentId(canonicalUrl)
}

/**
 * The candidate document codec — the FIRST gate, shared in spirit with the
 * bot (which validates the same shape). A hostile or corrupt document decodes
 * to null (a miss), never to a half-valid candidate.
 */
object SubmissionCandidateCodec {

    /** The moderation queue collection. */
    const val COLLECTION = "pending_submissions"

    const val MAX_TEXT_LEN = 300
    const val MAX_JSON_LEN = 20_000
    private const val MAX_URL_LEN = 2_000
    private const val MAX_HASH_LEN = 128

    /** sha256(canonicalUrl) — the queue key, deterministic across installs. */
    fun documentId(canonicalUrl: String): String {
        val normalized = canonicalUrl.trim()
        if (normalized.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun encode(candidate: SubmissionCandidate): Map<String, Any?> = buildMap {
        put("url", candidate.url.trim().take(MAX_URL_LEN))
        put("canonicalUrl", candidate.canonicalUrl.trim().take(MAX_URL_LEN))
        put("title", clean(candidate.title))
        put("author", clean(candidate.author))
        put("narrator", clean(candidate.narrator))
        put("coverUrl", clean(candidate.coverUrl))
        put("uploader", clean(candidate.uploader))
        put("durationSeconds", candidate.durationSeconds?.takeIf { it > 0 })
        put("chaptersCount", candidate.chaptersCount.coerceAtLeast(0))
        put("sourceId", candidate.sourceId.trim().take(MAX_TEXT_LEN))
        put("metadataJson", candidate.metadataJson?.take(MAX_JSON_LEN))
        put("submitterHash", candidate.submitterHash.take(MAX_HASH_LEN))
        put("playedAt", candidate.playedAt)
        put("createdAt", candidate.createdAt)
        put("state", candidate.state.name.lowercase())
        // The decision fields exist only after the bot decided.
        candidate.decidedAt?.let { put("decidedAt", it) }
        clean(candidate.decidedBy)?.let { put("decidedBy", it) }
        // An absent optional fact is OMITTED, not written as null: the bot's
        // contract (and the rules' optional-field checks) sees the same shape
        // this codec produces — pinned by SubmissionModerationContractTest.
    }.filterValues { it != null }

    /** @return null when the document is not a well-formed candidate. */
    fun decode(document: Map<String, Any?>?): SubmissionCandidate? {
        if (document == null) return null
        val url = (document["url"] as? String)?.trim().orEmpty()
        val canonicalUrl = (document["canonicalUrl"] as? String)?.trim().orEmpty()
        val title = (document["title"] as? String)?.trim().orEmpty()
        val submitterHash = (document["submitterHash"] as? String)?.trim().orEmpty()
        val state = (document["state"] as? String)?.let { raw ->
            SubmissionCandidate.State.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: return null
        // Identity is what makes a candidate addressable and decidable.
        if (url.isEmpty() || canonicalUrl.isEmpty() || title.isEmpty() || submitterHash.isEmpty()) {
            return null
        }
        return SubmissionCandidate(
            url = url,
            canonicalUrl = canonicalUrl,
            title = title,
            author = document["author"] as? String,
            narrator = document["narrator"] as? String,
            coverUrl = document["coverUrl"] as? String,
            uploader = document["uploader"] as? String,
            durationSeconds = (document["durationSeconds"] as? Number)?.toLong()?.takeIf { it > 0 },
            chaptersCount = ((document["chaptersCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
            sourceId = (document["sourceId"] as? String).orEmpty(),
            metadataJson = document["metadataJson"] as? String,
            submitterHash = submitterHash,
            playedAt = (document["playedAt"] as? Number)?.toLong() ?: 0L,
            createdAt = (document["createdAt"] as? Number)?.toLong() ?: 0L,
            state = state,
            decidedAt = (document["decidedAt"] as? Number)?.toLong(),
            decidedBy = document["decidedBy"] as? String
        )
    }

    private fun clean(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT_LEN)
}

/**
 * The one place a listener's verified submission becomes a queued candidate:
 * the canonical URL is derived HERE, and the raw uid is hashed HERE, so no
 * caller can accidentally queue an uncanonicalised link or a raw identity.
 */
object SubmissionCandidateFactory {

    /** sha256(uid) — the same discipline as the curator collections (#691). */
    fun submitterHash(uid: String?): String {
        if (uid.isNullOrBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Moderation T1 (#834) — a METADATA-ONLY candidate (the TG preview door):
     * the preview exposes no audio, so no playback verdict is POSSIBLE. The
     * anti-spam is the daily budget + canonical-URL dedup, and the curator's
     * moderation is the quality gate; [SubmissionCandidate.playedAt] is
     * honestly 0 — no verdict moment ever fired.
     */
    fun createMetadataOnly(
        url: String,
        canonical: String,
        title: String,
        uid: String?,
        createdAt: Long,
        author: String? = null,
        narrator: String? = null,
        coverUrl: String? = null,
        sourceId: String = "",
        metadataJson: String? = null
    ): SubmissionCandidate? {
        val hash = submitterHash(uid)
        if (url.isBlank() || canonical.isBlank() || title.isBlank() || hash.isEmpty()) return null
        return SubmissionCandidate(
            url = url.trim(),
            canonicalUrl = canonical.trim(),
            title = title.trim(),
            author = author,
            narrator = narrator,
            coverUrl = coverUrl,
            sourceId = sourceId,
            metadataJson = metadataJson,
            submitterHash = hash,
            playedAt = 0L,
            createdAt = createdAt
        )
    }

    /**
     * @param canonical the canonical form of [url] (the caller owns the
     *   source-specific canonicalisation, e.g. `SubmissionUrlCanonicalizer`).
     * @return the queued candidate, or null when identity or verification is
     *   missing — nothing unverified is ever queued.
     */
    fun create(
        url: String,
        canonical: String,
        title: String,
        uid: String?,
        playedAt: Long,
        createdAt: Long,
        author: String? = null,
        narrator: String? = null,
        coverUrl: String? = null,
        uploader: String? = null,
        durationSeconds: Long? = null,
        chaptersCount: Int = 0,
        sourceId: String = "",
        metadataJson: String? = null
    ): SubmissionCandidate? {
        val hash = submitterHash(uid)
        if (url.isBlank() || canonical.isBlank() || title.isBlank()) return null
        if (hash.isEmpty() || playedAt <= 0L) return null
        return SubmissionCandidate(
            url = url.trim(),
            canonicalUrl = canonical.trim(),
            title = title.trim(),
            author = author,
            narrator = narrator,
            coverUrl = coverUrl,
            uploader = uploader,
            durationSeconds = durationSeconds,
            chaptersCount = chaptersCount,
            sourceId = sourceId,
            metadataJson = metadataJson,
            submitterHash = hash,
            playedAt = playedAt,
            createdAt = createdAt
        )
    }
}
