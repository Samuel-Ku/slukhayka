package com.slukhayka.audiobooks.data.metadata

/**
 * Spec-49 T5 — the shared anonymous refusal aggregate: ONE document per
 * sourceId carrying a count only. A refusal is a source property — never
 * a book verdict — so even the data carries no Work, Edition, URL or
 * listener identity. Reads treat a corrupt document as a miss, never a
 * crash (the CoverCodec precedent).
 */
object SourceRefusalCountCodec {

    fun toMap(count: Long): Map<String, Any> = mapOf(FIELD_COUNT to count)

    fun fromMap(map: Map<String, Any>): Long? =
        (map[FIELD_COUNT] as? Number)?.toLong()?.takeIf { it >= 0 }

    const val FIELD_COUNT = "count"
}

/**
 * Spec-49 T5 — the per-device refusal vote that makes «one device counts
 * once» enforceable: document id `{sourceId}_{uid}` (the book_reviews
 * ownership precedent), payload of source and device only. The client
 * never reads vote documents back — only the anonymous aggregate is
 * displayed — but the shape is pinned here so the rules gate stays the
 * single source of truth both sides can reason about.
 */
object SourceRefusalVoteCodec {

    fun documentId(sourceId: String, uid: String): String = "${sourceId}_${uid}"

    /**
     * The vote payload, or null when either identity is blank (a blank
     * vote is never published).
     */
    fun toMap(sourceId: String, uid: String): Map<String, Any>? {
        if (sourceId.isBlank() || uid.isBlank()) return null
        return mapOf(FIELD_SOURCE_ID to sourceId, FIELD_UID to uid)
    }

    const val FIELD_SOURCE_ID = "sourceId"
    const val FIELD_UID = "uid"
}

/**
 * Spec-49 T5 — the batch bound of the refusal-count read behind the
 * «Аудіо джерел» screen: the eight catalogued sources in one pass, never
 * a request per row.
 */
object SourceRefusalReadLimits {
    const val MAX_BATCH = 25
}
