package com.slukhayka.audiobooks.data.reviews

/**
 * Spec-40 #277 — one listener review of a Work: stars (1..5), an optional
 * text body, and the optional edition tag («Начитка: …») naming the
 * narration the reviewer listened to. The document lives in Firestore's
 * `book_reviews` collection under the deterministic key
 * `${workId}_${uid}` — one review per listener per Work, idempotently
 * replaced on edit ([ListenerReviewCodec.documentId]).
 *
 * `authorName` is the listener's nickname at write time (a snapshot — later
 * nickname changes do not rewrite old reviews); `createdAt`/`editedAt`
 * are epoch millis.
 */
data class ListenerReview(
    val workId: String,
    val uid: String,
    val authorName: String,
    val rating: Int,
    val body: String? = null,
    val editionTag: String? = null,
    val createdAt: Long,
    val editedAt: Long? = null
)

/**
 * Spec-40 #277 — the sanity limits of a listener review, enforced on the
 * write path (truncate) and on decode (a hostile or corrupt document is a
 * miss, never a crash). The bounds mirror [com.slukhayka.audiobooks.data.metadata.BookProfileLimits]'s
 * defensiveness so the shared base stays bounded and the free tier keeps
 * its budget; the rating is exactly 1..5 stars.
 */
object ListenerReviewLimits {

    /** Star range — a rating outside it is invalid, never clamped. */
    const val MIN_RATING = 1
    const val MAX_RATING = 5

    /** A text-body bound — real reviews are paragraphs, not essays. */
    const val MAX_BODY_LEN = 2_000

    /** A nickname bound — real nicknames are far shorter. */
    const val MAX_AUTHOR_LEN = 100

    /** An edition-tag bound («Начитка: …» chip) — narrator labels are short. */
    const val MAX_EDITION_TAG_LEN = 200

    /** A Work-id bound — merge keys live far below this. */
    const val MAX_WORK_ID_LEN = 300

    fun isValidRating(rating: Int): Boolean = rating in MIN_RATING..MAX_RATING

    /**
     * The whole-document validity gate for a WRITE: identity fields must be
     * real (non-blank workId/uid), the rating in range. Optional strings may
     * be null or non-blank — blank is normalized away before this check by
     * the codec. Length caps are NOT re-checked here: the codec truncates on
     * encode, and the server rules are the second gate.
     */
    fun isWritable(review: ListenerReview): Boolean =
        review.workId.isNotBlank() &&
            review.workId.length <= MAX_WORK_ID_LEN &&
            review.uid.isNotBlank() &&
            review.authorName.isNotBlank() &&
            isValidRating(review.rating)
}

/**
 * The Firestore document codec for a [ListenerReview] — pure JVM so the
 * shape is unit-testable without Firebase. Document fields:
 *
 * ```
 * workId:      String   (the bibliographic Work — reviews are per Work,
 *                       shared across narrations)
 * uid:         String   (the listener identity — lane-a seam)
 * authorName:  String   (nickname snapshot at write time)
 * rating:      Int      (1..5 stars — required)
 * body:        String?  (only when present, ≤2000 chars)
 * editionTag:  String?  (only when present, ≤200 chars)
 * createdAt:   Long     (epoch millis)
 * editedAt:    Long?    (only when present)
 * ```
 *
 * [toMap] is the bounded write shape: optional blanks are dropped, field
 * lengths are truncated to the limits. [fromMap] is defensive on the read
 * side: a missing/mistyped required field, an out-of-range or fractional
 * rating, an over-limit string or a blank identity yields null — a corrupt
 * document is a miss, never a crash.
 */
object ListenerReviewCodec {

    /** The deterministic document key of one listener's review of a Work. */
    fun documentId(workId: String, uid: String): String = "${workId}_$uid"

    fun toMap(review: ListenerReview): Map<String, Any> = mapOf(
        "workId" to review.workId.take(ListenerReviewLimits.MAX_WORK_ID_LEN),
        "uid" to review.uid,
        "authorName" to review.authorName.take(ListenerReviewLimits.MAX_AUTHOR_LEN),
        "rating" to review.rating,
        "createdAt" to review.createdAt
    ) + optionalFields(review)

    /**
     * The whole decode is fail-closed: ANY problem inside (a mistyped field
     * throws from the helpers) collapses to null — a corrupt document is a
     * miss, never a crash.
     */
    fun fromMap(map: Map<String, Any>): ListenerReview? = runCatching { decode(map) }.getOrNull()

    private fun decode(map: Map<String, Any>): ListenerReview {
        val workId = (map["workId"] as? String)?.trim().orEmpty()
        require(workId.isNotBlank() && workId.length <= ListenerReviewLimits.MAX_WORK_ID_LEN) { "bad workId" }
        val uid = (map["uid"] as? String)?.trim().orEmpty()
        require(uid.isNotBlank()) { "bad uid" }
        val authorName = (map["authorName"] as? String)?.trim().orEmpty()
        require(authorName.isNotBlank() && authorName.length <= ListenerReviewLimits.MAX_AUTHOR_LEN) { "bad authorName" }
        val rating = wholeInt(map["rating"]) ?: throw IllegalArgumentException("mistyped rating")
        check(ListenerReviewLimits.isValidRating(rating)) { "rating out of range" }
        val createdAt = (map["createdAt"] as? Number)?.toLong() ?: throw IllegalArgumentException("mistyped createdAt")
        val body = boundedOptional(map["body"], ListenerReviewLimits.MAX_BODY_LEN)
        val editionTag = boundedOptional(map["editionTag"], ListenerReviewLimits.MAX_EDITION_TAG_LEN)
        val editedAt = (map["editedAt"] as? Number)?.toLong()
            ?: if (map.containsKey("editedAt")) throw IllegalArgumentException("mistyped editedAt") else null
        return ListenerReview(
            workId = workId,
            uid = uid,
            authorName = authorName,
            rating = rating,
            body = body,
            editionTag = editionTag,
            createdAt = createdAt,
            editedAt = editedAt
        )
    }

    /**
     * An integral rating only: a mistyped value (string) or a fractional
     * number (3.5 — a Double sneaking through `toInt()`'s truncation) is a
     * corrupt document, a miss.
     */
    private fun wholeInt(raw: Any?): Int? {
        val number = raw as? Number ?: return null
        val intValue = number.toInt()
        return intValue.takeIf { number.toDouble() == it.toDouble() }
    }

    /**
     * An optional string field: absent/null decodes to null; present but
     * blank also decodes to null (nothing worth showing); over-limit or
     * mistyped values THROW (the caller fail-closes the whole document) —
     * a hostile write is a miss, never silently clipped into a lie.
     */
    private fun boundedOptional(raw: Any?, maxLen: Int): String? = when (raw) {
        null -> null
        is String -> {
            val trimmed = raw.trim()
            when {
                trimmed.isEmpty() -> null
                trimmed.length > maxLen -> throw IllegalArgumentException("over-limit optional field")
                else -> trimmed
            }
        }
        else -> throw IllegalArgumentException("mistyped optional field")
    }

    private fun optionalFields(review: ListenerReview): Map<String, Any> = buildMap {
        review.body?.trim()?.takeIf { it.isNotEmpty() }?.let {
            put("body", it.take(ListenerReviewLimits.MAX_BODY_LEN))
        }
        review.editionTag?.trim()?.takeIf { it.isNotEmpty() }?.let {
            put("editionTag", it.take(ListenerReviewLimits.MAX_EDITION_TAG_LEN))
        }
        review.editedAt?.let { put("editedAt", it) }
    }
}
