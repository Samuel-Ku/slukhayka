package com.slukhayka.audiobooks.data.reviews

/**
 * ADR-0023 (#348) — one listener's stars-only verdict (1..5) on ONE Edition
 * («Оцінка начитки»): how good THIS narration is, independent of the
 * Work-level [ListenerReview] that never changes meaning. The document lives
 * in Firestore's `edition_ratings` collection under the deterministic key
 * `${workId}_${uid}_${editionId}` — one rating per listener per Edition,
 * idempotently replaced on edit ([NarrationRatingCodec.documentId]).
 *
 * No text, no tags — the review's territory (ADR-0023); new optional fields
 * later need no migration. `createdAt`/`editedAt` are epoch millis.
 */
data class NarrationRating(
    val workId: String,
    val uid: String,
    val editionId: String,
    val rating: Int,
    val createdAt: Long,
    val editedAt: Long? = null
)

/**
 * The sanity limits of a narration rating — same defensiveness doctrine as
 * [ListenerReviewLimits]: bounded identities, rating exactly 1..5.
 */
object NarrationRatingLimits {

    /** Star range — a rating outside it is invalid, never clamped. */
    const val MIN_RATING = 1
    const val MAX_RATING = 5

    /** A Work-id bound — merge keys live far below this. */
    const val MAX_WORK_ID_LEN = 300

    /**
     * An Edition-id bound — deterministic hashes are short; generous enough
     * for legacy fallback ids built from source urls.
     */
    const val MAX_EDITION_ID_LEN = 200

    fun isValidRating(rating: Int): Boolean = rating in MIN_RATING..MAX_RATING

    /** The write gate: real identity fields and an in-range rating. */
    fun isWritable(rating: NarrationRating): Boolean =
        rating.workId.isNotBlank() &&
            rating.workId.length <= MAX_WORK_ID_LEN &&
            rating.uid.isNotBlank() &&
            rating.editionId.isNotBlank() &&
            rating.editionId.length <= MAX_EDITION_ID_LEN &&
            isValidRating(rating.rating)
}

/**
 * The Firestore document codec for a [NarrationRating] — pure JVM like
 * [ListenerReviewCodec]. Document fields:
 *
 * ```
 * workId:     String  (the bibliographic Work — ratings group per Work)
 * uid:        String  (the listener identity)
 * editionId:  String  (the rendition identity — mergeKey|narrator|language hash)
 * rating:     Int     (1..5 stars — required)
 * createdAt:  Long    (epoch millis)
 * editedAt:   Long?   (only when present)
 * ```
 *
 * [toMap] is the bounded write shape; [fromMap] fails closed — a corrupt
 * document is a miss, never a crash.
 */
object NarrationRatingCodec {

    /** The deterministic document key: one rating per (Work × listener × Edition). */
    fun documentId(workId: String, uid: String, editionId: String): String =
        "${workId}_${uid}_$editionId"

    fun toMap(rating: NarrationRating): Map<String, Any> = mapOf(
        "workId" to rating.workId.take(NarrationRatingLimits.MAX_WORK_ID_LEN),
        "uid" to rating.uid,
        "editionId" to rating.editionId.take(NarrationRatingLimits.MAX_EDITION_ID_LEN),
        "rating" to rating.rating,
        "createdAt" to rating.createdAt
    ) + optionalFields(rating)

    fun fromMap(map: Map<String, Any>): NarrationRating? = runCatching { decode(map) }.getOrNull()

    private fun decode(map: Map<String, Any>): NarrationRating {
        val workId = (map["workId"] as? String)?.trim().orEmpty()
        require(workId.isNotBlank() && workId.length <= NarrationRatingLimits.MAX_WORK_ID_LEN) { "bad workId" }
        val uid = (map["uid"] as? String)?.trim().orEmpty()
        require(uid.isNotBlank()) { "bad uid" }
        val editionId = (map["editionId"] as? String)?.trim().orEmpty()
        require(editionId.isNotBlank() && editionId.length <= NarrationRatingLimits.MAX_EDITION_ID_LEN) { "bad editionId" }
        val rating = wholeInt(map["rating"]) ?: throw IllegalArgumentException("mistyped rating")
        check(NarrationRatingLimits.isValidRating(rating)) { "rating out of range" }
        val createdAt = (map["createdAt"] as? Number)?.toLong() ?: throw IllegalArgumentException("mistyped createdAt")
        val editedAt = (map["editedAt"] as? Number)?.toLong()
            ?: if (map.containsKey("editedAt")) throw IllegalArgumentException("mistyped editedAt") else null
        return NarrationRating(
            workId = workId,
            uid = uid,
            editionId = editionId,
            rating = rating,
            createdAt = createdAt,
            editedAt = editedAt
        )
    }

    /** An integral rating only — a fractional 3.5 sneaking through is corrupt. */
    private fun wholeInt(raw: Any?): Int? {
        val number = raw as? Number ?: return null
        val intValue = number.toInt()
        return intValue.takeIf { number.toDouble() == it.toDouble() }
    }

    private fun optionalFields(rating: NarrationRating): Map<String, Any> = buildMap {
        rating.editedAt?.let { put("editedAt", it) }
    }
}

/**
 * The narration-ratings store behind a pure JVM seam, shaped exactly like
 * [ListenerReviewsStore]: best-effort and silent by contract; the policy
 * lives in default methods over a minimal document transport so fixture
 * tests pin behaviour without Firebase.
 *
 * Documents live under `${workId}_${uid}_${editionId}`; every list comes
 * back NEWEST FIRST by `createdAt`.
 */
interface NarrationRatingsStore {

    /**
     * Every narration rating of one Work (across all its Editions — the UI
     * filters by editionId). A miss, a failure or corrupt documents
     * contribute nothing — empty on a silent work, never an error.
     */
    suspend fun getForWork(workId: String): List<NarrationRating> {
        val documents = runCatching { queryWorkDocuments(workId) }.getOrNull() ?: return emptyList()
        return decode(documents)
    }

    /**
     * Idempotent write of one rating (`set()` under the deterministic key).
     * An invalid rating is refused with false BEFORE any I/O; a failing
     * transport reports false. True means durably accepted locally (offline:
     * the persistence queue).
     */
    suspend fun putRating(rating: NarrationRating): Boolean {
        if (!NarrationRatingLimits.isWritable(rating)) return false
        return runCatching {
            setDocument(
                NarrationRatingCodec.documentId(rating.workId, rating.uid, rating.editionId),
                NarrationRatingCodec.toMap(rating)
            )
        }.getOrDefault(false)
    }

    /** Best-effort removal of one listener's rating; false on any failure. */
    suspend fun deleteRating(workId: String, uid: String, editionId: String): Boolean =
        runCatching {
            removeDocument(NarrationRatingCodec.documentId(workId, uid, editionId))
        }.getOrDefault(false)

    // ---------------------------------------------------------------------
    // Transport — the ONLY part an implementation supplies.
    // ---------------------------------------------------------------------

    /** Raw documents of one Work's ratings (any order; the seam sorts). */
    suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>>

    /** One idempotent document write; true when durably accepted locally. */
    suspend fun setDocument(documentId: String, document: Map<String, Any>): Boolean

    /** One document delete; true when accepted. */
    suspend fun removeDocument(documentId: String): Boolean

    private fun decode(documents: List<Map<String, Any>>): List<NarrationRating> =
        documents.mapNotNull { NarrationRatingCodec.fromMap(it) }
            .sortedByDescending { it.createdAt }
}
