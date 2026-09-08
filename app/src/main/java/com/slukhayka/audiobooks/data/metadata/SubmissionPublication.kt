package com.slukhayka.audiobooks.data.metadata

/**
 * ADR-0035 / #605 — ONE verified listener submission published to the shared
 * base: the link, the mode of access, and the metadata deltas (Work identity,
 * duration, cover URL as a source claim, description as a source claim). The
 * payload is pure JVM — no Firestore types — so a future P2P transport can
 * carry the same shape (ADR-0028 p. 3). Audio bytes never ride this document;
 * playback on every install goes from the source-original through its own
 * [com.slukhayka.audiobooks.data.source.StreamUrlResolver] route.
 *
 * Chapters are the SUBMITTER's observed boundaries (ADR-0014) with canonical
 * watch URLs — signed stream URLs are never persisted (ADR-0007). A
 * metadata-only publication (no chapters — e.g. a TG preview that cannot
 * play without login, ADR-0035 p. 13) materialises identity honestly and
 * playback surfaces the absent source as unavailable, never fabricated.
 */
data class SubmissionPublication(
    /** The submitted link, normalized (trimmed). Store key = its hash. */
    val sourceUrl: String,
    /** The mode of access the link grants — see [SubmissionAccessMode]. */
    val accessMode: String,
    /** The claimed Work identity (TitleNormalizer output; author never invented). */
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    /** Description as a provenance-carrying source claim — never Work-level truth. */
    val description: String? = null,
    /** Cover URL as a source claim (ADR-0020 last priority level, ADR-0035). */
    val coverUrl: String? = null,
    /** Total duration observed in the metadata (single video only — honest). */
    val durationSeconds: Long? = null,
    /** Observed chapter boundaries with canonical watch URLs. */
    val chapters: List<SubmissionChapter> = emptyList(),
    /** The moment a REAL playback verdict fired (the publication gate). */
    val verifiedAt: Long,
    /** When the publication document was written. */
    val submittedAt: Long,
    /** Bounded anonymous submitter id (daily-limit accounting is #607's policy). */
    val submitterId: String
)

/** One observed chapter boundary of a submission: display title + watch URL. */
data class SubmissionChapter(
    val title: String,
    val watchUrl: String
)

/** The known modes of access a submission link grants. */
object SubmissionAccessMode {
    /** A YouTube video or playlist — playable via the yt-dlp resolver route. */
    const val YOUTUBE = "youtube"

    /** A Telegram public-preview page — metadata-only until a prototype proves playback (ADR-0035 p. 13). */
    const val TG_PREVIEW = "tg_preview"
}

/**
 * ADR-0035 — the honest-data sanity limits of one submission document,
 * enforced on encode (bound) and on decode (a hostile/corrupt document is a
 * miss, never a crash). Mirrors the [BookProfileLimits] precedent.
 */
object SubmissionPublicationLimits {

    /** The chapter cap — long books have tens, never thousands, of parts. */
    const val MAX_CHAPTERS = 500

    /** A title/author/narrator bound — real titles are far shorter. */
    const val MAX_TEXT_LEN = 300

    /** A description bound — real blurbs are a few hundred chars. */
    const val MAX_DESCRIPTION_LEN = 5_000

    /** A URL bound (source, cover, watch) — real links live far below this. */
    const val MAX_URL_LEN = 2_000

    /** The access-mode label bound. */
    const val MAX_ACCESS_MODE_LEN = 40

    /** The submitter id bound. */
    const val MAX_SUBMITTER_ID_LEN = 80

    /** A plausible claimed duration ceiling — 100 hours, generous but bounded. */
    const val MAX_DURATION_SECONDS = 100L * 60 * 60

    fun isHttpUrl(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    fun isPlausibleDuration(durationSeconds: Long): Boolean =
        durationSeconds > 0 && durationSeconds <= MAX_DURATION_SECONDS
}

/**
 * The shared-base document codec for a [SubmissionPublication] — pure JVM so
 * the shape is unit-testable without Firebase. Document fields:
 *
 * ```
 * sourceUrl:    String   (required — http(s), bounded; the normalized link)
 * accessMode:   String   (required — bounded label)
 * title:        String   (required — non-blank, bounded)
 * author:       String?  (optional; blank dropped on encode)
 * narrator:     String?  (optional)
 * description:  String?  (optional — provenance-carrying source claim)
 * coverUrl:     String?  (optional — http(s), bounded)
 * durationSeconds: Long? (optional — plausible positive, bounded)
 * chapters:     [ {title, watchUrl} ]  (observed boundaries, watch URLs http(s))
 * verifiedAt:   Long     (required — >= 0)
 * submittedAt:  Long     (required — >= 0)
 * submitterId:  String   (required — bounded)
 * ```
 *
 * [toMap] bounds the write; [fromMap] is defensive: a missing/mistyped
 * required field, a non-http URL, a blank title or an oversized chapter list
 * yields null — a corrupt/empty/redundant document is a MISS, never a crash
 * (the CoverCodec precedent, spec-601 testing decision).
 */
object SubmissionPublicationCodec {

    /** The deterministic document key of one submission — the normalized URL's hash (store-level URL dedup). */
    fun documentId(sourceUrl: String): String = Integer.toHexString(sourceUrl.trim().hashCode())

    fun toMap(publication: SubmissionPublication): Map<String, Any> = mapOf(
        "sourceUrl" to publication.sourceUrl.trim().take(SubmissionPublicationLimits.MAX_URL_LEN),
        "accessMode" to publication.accessMode.take(SubmissionPublicationLimits.MAX_ACCESS_MODE_LEN),
        "title" to publication.title.take(SubmissionPublicationLimits.MAX_TEXT_LEN),
        "verifiedAt" to publication.verifiedAt,
        "submittedAt" to publication.submittedAt,
        "submitterId" to publication.submitterId.take(SubmissionPublicationLimits.MAX_SUBMITTER_ID_LEN)
    ) + optionalFields(publication)

    fun fromMap(map: Map<String, Any>): SubmissionPublication? {
        val sourceUrl = (map["sourceUrl"] as? String)?.trim().orEmpty()
        if (!SubmissionPublicationLimits.isHttpUrl(sourceUrl) ||
            sourceUrl.length > SubmissionPublicationLimits.MAX_URL_LEN
        ) return null
        val accessMode = (map["accessMode"] as? String)?.trim().orEmpty()
        if (accessMode.isEmpty() || accessMode.length > SubmissionPublicationLimits.MAX_ACCESS_MODE_LEN) return null
        val title = (map["title"] as? String)?.trim().orEmpty()
        if (title.isEmpty() || title.length > SubmissionPublicationLimits.MAX_TEXT_LEN) return null
        val verifiedAt = (map["verifiedAt"] as? Number)?.toLong() ?: return null
        val submittedAt = (map["submittedAt"] as? Number)?.toLong() ?: return null
        val submitterId = (map["submitterId"] as? String)?.trim().orEmpty()
        if (submitterId.isEmpty() || submitterId.length > SubmissionPublicationLimits.MAX_SUBMITTER_ID_LEN) return null
        if (verifiedAt < 0 || submittedAt < 0) return null

        val rawChapters = when (val raw = map["chapters"]) {
            is List<*> -> raw
            else -> emptyList<Any>()
        }
        if (rawChapters.size > SubmissionPublicationLimits.MAX_CHAPTERS) return null
        val chapters = mutableListOf<SubmissionChapter>()
        for (raw in rawChapters) {
            chapters += chapterFromMap(raw as? Map<*, *> ?: continue) ?: continue
        }
        val coverUrl = (map["coverUrl"] as? String)?.trim().orEmpty()
        if (coverUrl.isNotEmpty() &&
            (!SubmissionPublicationLimits.isHttpUrl(coverUrl) ||
                coverUrl.length > SubmissionPublicationLimits.MAX_URL_LEN)
        ) return null

        return SubmissionPublication(
            sourceUrl = sourceUrl,
            accessMode = accessMode,
            title = title,
            author = boundedOptional(map["author"]),
            narrator = boundedOptional(map["narrator"]),
            description = (map["description"] as? String)
                ?.take(SubmissionPublicationLimits.MAX_DESCRIPTION_LEN),
            coverUrl = coverUrl.ifEmpty { null },
            durationSeconds = (map["durationSeconds"] as? Number)?.toLong()
                ?.takeIf { it > 0 && it <= SubmissionPublicationLimits.MAX_DURATION_SECONDS },
            chapters = chapters,
            verifiedAt = verifiedAt,
            submittedAt = submittedAt,
            submitterId = submitterId
        )
    }

    private fun optionalFields(publication: SubmissionPublication): Map<String, Any> = buildMap {
        publication.author?.takeIf { it.isNotBlank() }?.let {
            put("author", it.take(SubmissionPublicationLimits.MAX_TEXT_LEN))
        }
        publication.narrator?.takeIf { it.isNotBlank() }?.let {
            put("narrator", it.take(SubmissionPublicationLimits.MAX_TEXT_LEN))
        }
        publication.description?.takeIf { it.isNotBlank() }?.let {
            put("description", it.take(SubmissionPublicationLimits.MAX_DESCRIPTION_LEN))
        }
        publication.coverUrl?.takeIf { it.isNotBlank() }?.let {
            put("coverUrl", it.take(SubmissionPublicationLimits.MAX_URL_LEN))
        }
        publication.durationSeconds
            ?.takeIf(SubmissionPublicationLimits::isPlausibleDuration)
            ?.let { put("durationSeconds", it) }
        if (publication.chapters.isNotEmpty()) {
            put(
                "chapters",
                publication.chapters
                    .filter { SubmissionPublicationLimits.isHttpUrl(it.watchUrl) }
                    .take(SubmissionPublicationLimits.MAX_CHAPTERS)
                    .map { chapter -> chapterToMap(chapter) }
            )
        }
    }

    private fun boundedOptional(value: Any?): String? =
        (value as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= SubmissionPublicationLimits.MAX_TEXT_LEN }

    private fun chapterToMap(chapter: SubmissionChapter): Map<String, Any> = mapOf(
        "title" to chapter.title.take(SubmissionPublicationLimits.MAX_TEXT_LEN),
        "watchUrl" to chapter.watchUrl.take(SubmissionPublicationLimits.MAX_URL_LEN)
    )

    private fun chapterFromMap(map: Map<*, *>): SubmissionChapter? {
        val watchUrl = (map["watchUrl"] as? String)?.trim().orEmpty()
        if (!SubmissionPublicationLimits.isHttpUrl(watchUrl) ||
            watchUrl.length > SubmissionPublicationLimits.MAX_URL_LEN
        ) return null
        return SubmissionChapter(
            title = (map["title"] as? String ?: "").take(SubmissionPublicationLimits.MAX_TEXT_LEN),
            watchUrl = watchUrl
        )
    }
}