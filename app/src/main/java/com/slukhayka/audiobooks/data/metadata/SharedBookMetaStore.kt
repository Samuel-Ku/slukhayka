package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.duration.DurationBuckets

/**
 * Spec-30 T2 (#217) — the shared book-metadata store behind a pure JVM seam,
 * the tracer bullet of the anonymous shared metadata cache: a duration that
 * only exists in the shared base appears in search results without any local
 * derivation. Mirrors the [com.slukhayka.audiobooks.data.universe.SharedUniverseStore]
 * shape exactly: best-effort and silent by contract — a miss, a failure or an
 * unreadable document all yield null / an empty map, and the caller simply
 * falls through to the local database (and, behind it, the source) as before.
 *
 * The identity is the book's **Edition id** (`EditionId.forBook` —
 * `hash(mergeKey|narrator|language)`): duration is rendition-scoped
 * (ADR-0010 — two narrations of one Work never share a duration), and the
 * same key the local `editions` rows already use. Covers land here later
 * (spec-30) keyed by the Work mergeKey; this tracer bullet carries duration
 * only.
 */
interface SharedBookMetaStore {
    /** The shared duration of one Edition, or null on miss/failure. */
    suspend fun getDuration(editionId: String): Long?

    /**
     * Spec-30 T2 — BATCH read for the books actually visible on screen: one
     * shared-base read for the whole set (chunked internally where the
     * transport demands), never a request per book. A miss or a failing chunk
     * contributes nothing — the map simply lacks those ids.
     */
    suspend fun getDurations(editionIds: List<String>): Map<String, Long>

    /**
     * Best-effort write-back of one derived duration into the shared base,
     * keyed by the SAME Edition id the read path uses, so the next user reads
     * it instead of deriving again. Carries the [DurationProvenance]
     * (source, derivedAt). Idempotent by contract (a document key is
     * replaced, never duplicated — Firestore set()); a failing write
     * contributes nothing.
     */
    suspend fun putDuration(
        editionId: String,
        durationSeconds: Long,
        provenance: DurationProvenance
    )
}

/**
 * Spec-30 — the provenance of one shared duration: where it came from and
 * when it was derived. Written with every put; reads ignore the fields, so
 * older documents decode fine.
 */
data class DurationProvenance(
    /** The origin of the duration: [SOURCE_DERIVED] today. */
    val source: String,
    val derivedAt: Long
) {
    companion object {
        /** A duration derived from real source metadata (page, stream probe). */
        const val SOURCE_DERIVED = "derived"
    }
}

/**
 * Spec-30 — the honest-data sanity gate for shared durations. A value is
 * plausible only when it is a real known duration (positive, never the
 * fabricated 4:00:00 legacy sentinel — [DurationBuckets.hasKnownDuration])
 * AND below a generous ceiling. Enforced on every read (a wild/corrupt
 * document is a miss, never a crash) and on the write path's callers.
 */
object DurationSanity {
    /** The plausible ceiling: 100 hours — generous, bounded. */
    const val MAX_PLAUSIBLE_SECONDS = 100L * 60 * 60

    fun isPlausible(durationSeconds: Long): Boolean =
        DurationBuckets.hasKnownDuration(durationSeconds) && durationSeconds <= MAX_PLAUSIBLE_SECONDS
}

/**
 * The Firestore document codec for a shared duration — pure JVM so the shape
 * is unit-testable without Firebase. Document fields:
 *
 * ```
 * durationSeconds: Long   (the rendition's total duration)
 * source:         String  (provenance — e.g. "derived")
 * derivedAt:      Long    (provenance — when the duration was derived)
 * ```
 *
 * [fromMap] is defensive: any missing/mistyped required field or an
 * implausible duration yields null (a corrupt document is a miss, never a
 * crash).
 */
object SharedDurationCodec {

    fun toMap(durationSeconds: Long, provenance: DurationProvenance): Map<String, Any> = mapOf(
        "durationSeconds" to durationSeconds,
        "source" to provenance.source,
        "derivedAt" to provenance.derivedAt
    )

    fun fromMap(map: Map<String, Any>): Long? {
        val duration = (map["durationSeconds"] as? Number)?.toLong() ?: return null
        return duration.takeIf { DurationSanity.isPlausible(it) }
    }
}
