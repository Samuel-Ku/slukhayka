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

    /**
     * Spec-32 T1 (#231) — the shared FULL resolved profile of one
     * Source×Edition, or null on a miss, a failure or a corrupt document.
     * Keyed by `(sourceId, editionId)` — the same identity the import
     * resolves — because stream URLs are per-source: the same narration on
     * 4read and on sound-books.net has DIFFERENT tracks.
     */
    suspend fun getProfile(sourceId: String, editionId: String): BookProfile?

    /**
     * Spec-32 T1 — best-effort write-back of one resolved profile, keyed by
     * the SAME `(sourceId, editionId)` the read path uses, so the next
     * listener reads it instead of re-resolving the source page. Idempotent
     * by contract (a document key is replaced, never duplicated); a failing
     * write contributes nothing. The [ProfileProvenance] (source, resolvedAt)
     * rides with the write — the freshness decision later reads it back.
     */
    suspend fun putProfile(
        sourceId: String,
        editionId: String,
        profile: BookProfile,
        provenance: ProfileProvenance
    )
}

/**
 * Spec-32 T1 — the provenance of one shared profile: where the resolution
 * came from and when it was resolved. Written with every put; reads ignore
 * the fields (older documents decode fine) until the freshness pass needs
 * the timestamp.
 */
data class ProfileProvenance(
    val source: String,
    val resolvedAt: Long
)

/**
 * Spec-32 T1 — one chapter of a shared profile: the playable stream URL
 * (per-source — the owning Source's Referer/User-Agent rules apply at play
 * time) plus the title and duration the page carried.
 */
data class ProfileChapter(
    val title: String,
    val streamUrl: String,
    val durationSeconds: Long = 0L
)

/**
 * Spec-32 T1 — the full resolved profile of one Source×Edition: everything a
 * book page yields (description, narrator, series, genres, rating, cover,
 * ordered chapters with stream URLs, total duration). What a listener who
 * already opened the book has — cached so the next one skips the page fetch.
 */
data class BookProfile(
    val description: String = "",
    val narrator: String = "",
    val seriesTitle: String? = null,
    val seriesIndex: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val coverImageUrl: String? = null,
    val chapters: List<ProfileChapter> = emptyList(),
    val totalDurationSeconds: Long? = null
)

/**
 * Spec-32 T1 — the sanity limits of a shared profile, enforced on the write
 * path (truncate / drop) and on decode (a hostile or corrupt document is a
 * miss): http(s)-only stream URLs, a chapter cap, and field-length bounds so
 * the shared base stays bounded and the free tier keeps its budget.
 */
object BookProfileLimits {

    /** The chapter cap — long books have tens, never thousands, of parts. */
    const val MAX_CHAPTERS = 500

    /** A chapter/author title bound — real titles are far shorter. */
    const val MAX_TITLE_LEN = 300

    /** A description bound — real blurbs are a few hundred chars. */
    const val MAX_DESCRIPTION_LEN = 5_000

    /** The genre count bound — real books carry a handful of genres. */
    const val MAX_GENRES = 20

    /** A URL bound (cover) — real covers live far below this. */
    const val MAX_URL_LEN = 2_000

    fun isHttpUrl(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")
}

/**
 * The Firestore document codec for a [BookProfile] — pure JVM so the shape is
 * unit-testable without Firebase. Document fields:
 *
 * ```
 * description:       String
 * narrator:          String
 * seriesTitle:       String?        (only when present)
 * seriesIndex:       Int?           (only when present)
 * genres:            [String]
 * rating:            Double?        (only when present)
 * coverImageUrl:     String?        (only when present)
 * totalDurationSeconds: Long?       (only when present)
 * chapters:          [ {title, streamUrl, durationSeconds} ]
 * ```
 *
 * [toMap] bounds the write (chapter cap, field caps, http(s)-only stream
 * URLs); [fromMap] is defensive: a missing/mistyped chapters list, an
 * oversized chapter list, a non-http stream URL, or a corrupt chapter entry
 * is dropped or yields null — a corrupt document is a miss, never a crash.
 */
object BookProfileCodec {

    fun toMap(profile: BookProfile, provenance: ProfileProvenance): Map<String, Any> = mapOf(
        "source" to provenance.source,
        "resolvedAt" to provenance.resolvedAt,
        "description" to profile.description.take(BookProfileLimits.MAX_DESCRIPTION_LEN),
        "narrator" to profile.narrator.take(BookProfileLimits.MAX_TITLE_LEN),
        "genres" to profile.genres.take(BookProfileLimits.MAX_GENRES).map { it.take(BookProfileLimits.MAX_TITLE_LEN) },
        "chapters" to profile.chapters
            .filter { BookProfileLimits.isHttpUrl(it.streamUrl) }
            .take(BookProfileLimits.MAX_CHAPTERS)
            .map { chapterToMap(it) }
    ) + optionalFields(profile)

    fun fromMap(map: Map<String, Any>): BookProfile? {
        val rawChapters = map["chapters"] as? List<*> ?: return null
        if (rawChapters.size > BookProfileLimits.MAX_CHAPTERS) return null
        val chapters = mutableListOf<ProfileChapter>()
        for (raw in rawChapters) {
            chapters += chapterFromMap(raw as? Map<*, *> ?: continue) ?: continue
        }
        return BookProfile(
            description = (map["description"] as? String ?: "").take(BookProfileLimits.MAX_DESCRIPTION_LEN),
            narrator = (map["narrator"] as? String ?: "").take(BookProfileLimits.MAX_TITLE_LEN),
            seriesTitle = (map["seriesTitle"] as? String)?.take(BookProfileLimits.MAX_TITLE_LEN),
            seriesIndex = (map["seriesIndex"] as? Number)?.toInt(),
            genres = (map["genres"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.take(BookProfileLimits.MAX_GENRES)
                ?.map { it.take(BookProfileLimits.MAX_TITLE_LEN) }
                ?: emptyList(),
            rating = (map["rating"] as? Number)?.toDouble(),
            coverImageUrl = (map["coverImageUrl"] as? String)?.take(BookProfileLimits.MAX_URL_LEN),
            chapters = chapters,
            totalDurationSeconds = (map["totalDurationSeconds"] as? Number)?.toLong()
                ?.takeIf { DurationSanity.isPlausible(it) }
        )
    }

    private fun optionalFields(profile: BookProfile): Map<String, Any> = buildMap {
        profile.seriesTitle?.let { put("seriesTitle", it.take(BookProfileLimits.MAX_TITLE_LEN)) }
        profile.seriesIndex?.let { put("seriesIndex", it) }
        profile.rating?.let { put("rating", it) }
        profile.coverImageUrl?.let { put("coverImageUrl", it.take(BookProfileLimits.MAX_URL_LEN)) }
        profile.totalDurationSeconds?.let { put("totalDurationSeconds", it) }
    }

    private fun chapterToMap(chapter: ProfileChapter): Map<String, Any> = mapOf(
        "title" to chapter.title.take(BookProfileLimits.MAX_TITLE_LEN),
        "streamUrl" to chapter.streamUrl,
        "durationSeconds" to chapter.durationSeconds
    )

    private fun chapterFromMap(map: Map<*, *>): ProfileChapter? {
        val streamUrl = map["streamUrl"] as? String ?: return null
        if (!BookProfileLimits.isHttpUrl(streamUrl)) return null
        return ProfileChapter(
            title = (map["title"] as? String ?: "").take(BookProfileLimits.MAX_TITLE_LEN),
            streamUrl = streamUrl,
            durationSeconds = (map["durationSeconds"] as? Number)?.toLong() ?: 0L
        )
    }
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
