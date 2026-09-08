package com.slukhayka.audiobooks.data.metadata

/**
 * ADR-0035 / #607 — ONE curator-placed Shared Tombstone in the shared base:
 * the ADR-0005 local tombstone model continued onto the collective channel.
 * The target is either a whole Work (by mergeKey — the bibliographic
 * identity every install derives the same way) or ONE published Source (by
 * its normalized URL). Every install consumes the document through the
 * shared-tombstone delta lane and enforces it with the LOCAL tombstone
 * machinery — the marker blocks future materialization, and the source's
 * catalog claim rows are removed where no local copy owns them (local copies
 * and Listening State always survive).
 *
 * The payload is pure JVM — no Firestore types (ADR-0028 p. 3).
 */
data class SharedTombstone(
    /** The target scope — [TombstoneTargetKind.WORK] or [TombstoneTargetKind.SOURCE]. */
    val targetKind: String,
    /** WORK target: the normalized Work identity (mergeKey). */
    val mergeKey: String? = null,
    /** SOURCE target: the normalized submitted URL. */
    val sourceUrl: String? = null,
    /** The curator's bounded reason — provenance, never displayed as truth. */
    val reason: String? = null,
    /** When the curator placed the tombstone. */
    val placedAt: Long,
    /** Bounded curator id. */
    val curatorId: String
)

/** The known target scopes of a [SharedTombstone]. */
object TombstoneTargetKind {
    const val WORK = "work"
    const val SOURCE = "source"
}

/**
 * ADR-0035 — the honest-data sanity limits of one shared tombstone document,
 * enforced on encode (bound) and on decode (a hostile/corrupt document is a
 * miss, never a crash).
 */
object SharedTombstoneLimits {

    /** A mergeKey/URL/curator-id bound — real identities are far shorter. */
    const val MAX_TEXT_LEN = 2_000

    /** The curator reason bound — real reasons are a few hundred chars. */
    const val MAX_REASON_LEN = 1_000

    const val MAX_CURATOR_ID_LEN = 80

    fun isHttpUrl(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")
}

/**
 * The shared-base document codec for a [SharedTombstone] — pure JVM so the
 * shape is unit-testable without Firebase (the CoverCodec precedent).
 * Document fields:
 *
 * ```
 * targetKind: String   (required — "work" | "source")
 * mergeKey:   String?  (required for work targets — bounded)
 * sourceUrl:  String?  (required for source targets — http(s), bounded)
 * reason:     String?  (optional — bounded)
 * placedAt:   Long     (required — >= 0)
 * curatorId:  String   (required — bounded)
 * ```
 *
 * The document key is deterministic per TARGET, so re-placing the same
 * tombstone REPLACE-no-ops. [fromMap] is defensive: a missing/mistyped
 * field, an unknown target kind or a missing target identity yields null —
 * a corrupt/empty/redundant document is a MISS, never a crash.
 */
object SharedTombstoneCodec {

    /** The deterministic document key of one shared tombstone — per TARGET. */
    fun documentId(tombstone: SharedTombstone): String? = when (tombstone.targetKind) {
        TombstoneTargetKind.WORK -> tombstone.mergeKey
            ?.takeIf { it.isNotBlank() }
            ?.let { "work-${Integer.toHexString(it.hashCode())}" }

        TombstoneTargetKind.SOURCE -> tombstone.sourceUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "src-${Integer.toHexString(it.hashCode())}" }

        else -> null
    }

    fun toMap(tombstone: SharedTombstone): Map<String, Any>? {
        val documentId = documentId(tombstone) ?: return null
        return buildMap {
            put("documentId", documentId)
            put("targetKind", tombstone.targetKind)
            put("placedAt", tombstone.placedAt)
            put("curatorId", tombstone.curatorId.take(SharedTombstoneLimits.MAX_CURATOR_ID_LEN))
            tombstone.mergeKey?.takeIf { it.isNotBlank() }?.let {
                put("mergeKey", it.take(SharedTombstoneLimits.MAX_TEXT_LEN))
            }
            tombstone.sourceUrl?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("sourceUrl", it.take(SharedTombstoneLimits.MAX_TEXT_LEN))
            }
            tombstone.reason?.takeIf { it.isNotBlank() }?.let {
                put("reason", it.take(SharedTombstoneLimits.MAX_REASON_LEN))
            }
        }
    }

    fun fromMap(map: Map<String, Any>): SharedTombstone? {
        val targetKind = (map["targetKind"] as? String)?.trim().orEmpty()
        if (targetKind != TombstoneTargetKind.WORK && targetKind != TombstoneTargetKind.SOURCE) return null
        val placedAt = (map["placedAt"] as? Number)?.toLong() ?: return null
        val curatorId = (map["curatorId"] as? String)?.trim().orEmpty()
        if (curatorId.isEmpty() || curatorId.length > SharedTombstoneLimits.MAX_CURATOR_ID_LEN) return null
        if (placedAt < 0) return null

        val mergeKey = (map["mergeKey"] as? String)?.trim().orEmpty()
        val sourceUrl = (map["sourceUrl"] as? String)?.trim().orEmpty()
        return when (targetKind) {
            TombstoneTargetKind.WORK -> {
                if (mergeKey.isEmpty() || mergeKey.length > SharedTombstoneLimits.MAX_TEXT_LEN) return null
                SharedTombstone(
                    targetKind = targetKind,
                    mergeKey = mergeKey,
                    reason = boundedReason(map),
                    placedAt = placedAt,
                    curatorId = curatorId
                )
            }

            TombstoneTargetKind.SOURCE -> {
                if (!SharedTombstoneLimits.isHttpUrl(sourceUrl) ||
                    sourceUrl.length > SharedTombstoneLimits.MAX_TEXT_LEN
                ) return null
                SharedTombstone(
                    targetKind = targetKind,
                    sourceUrl = sourceUrl,
                    reason = boundedReason(map),
                    placedAt = placedAt,
                    curatorId = curatorId
                )
            }

            else -> null
        }
    }

    private fun boundedReason(map: Map<String, Any>): String? =
        (map["reason"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= SharedTombstoneLimits.MAX_REASON_LEN }
}

/** The ordered-page cursor of the shared tombstone collection (mirrors [SubmissionCursor]). */
data class SharedTombstoneCursor(
    val placedAt: Long,
    val documentId: String
)

/** One bounded page of shared tombstones plus the continuation cursor. */
data class SharedTombstonePage(
    val tombstones: List<SharedTombstone>,
    val nextCursor: SharedTombstoneCursor?
)

/** The page bound of the shared tombstone collection (mirrors [SubmissionPageLimits]). */
object SharedTombstonePageLimits {
    const val MAX_PAGE_SIZE = 100

    fun bounded(requested: Int): Int = when {
        requested <= 0 -> 0
        else -> requested.coerceAtMost(MAX_PAGE_SIZE)
    }
}