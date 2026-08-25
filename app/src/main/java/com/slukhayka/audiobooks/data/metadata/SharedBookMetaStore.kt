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
 * same key the local `editions` rows already use. Other public metadata keeps
 * its own explicit identity: facets use `(kind, entityId, Source)`, covers
 * use the Work mergeKey, and profiles use Source×Edition.
 */
interface SharedBookMetaStore {
    /** One compact Work/Edition assertion, addressed by stable entity+Source identity. */
    suspend fun getFacet(key: FacetAssertionKey): FacetAssertion? = null

    /** Best-effort full-shape create/update. Invalid assertions contribute nothing. */
    suspend fun putFacet(assertion: FacetAssertion) = Unit

    /** Bounded ordered remote delta page; applying/committing it belongs to spec-42 #308. */
    suspend fun getFacetPage(after: FacetCursor?, limit: Int): FacetPage = FacetPage(emptyList(), null)

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
     * it instead of deriving again. Carries bounded [DurationProvenance]
     * (source, method, derivedAt). The first plausible value is canonical;
     * repeats are no-ops and a materially different value creates one
     * deterministic conflict record without replacing the canonical fact.
     * A failing write contributes nothing.
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
     * Spec-32 T3 (#233) — the profile WITH its resolved-at stamp, so the
     * read-skip path can decide freshness and fail open on a stale entry
     * (serve the old profile when the re-fetch fails). Null on miss/failure.
     */
    suspend fun getProfileEntry(sourceId: String, editionId: String): SharedProfileEntry?

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

    /**
     * Spec-30 T3 (#218) — the shared CANONICAL cover URL of one Work, or
     * null on a miss or a failure. Keyed by the Work **mergeKey** (one cover
     * per Work, shared across narrations — the identity covers live on,
     * unlike the Edition-scoped duration and the Source×Edition profile).
     * The document holds the URL only; the image itself is never rehosted.
     */
    suspend fun getCover(mergeKey: String): String?

    /**
     * Spec-30 T3 — BATCH read for the covers actually visible on screen: one
     * shared-base read for the whole set (chunked internally where the
     * transport demands), never a request per Work. A miss or a failing
     * chunk contributes nothing — the map simply lacks those keys.
     */
    suspend fun getCovers(mergeKeys: List<String>): Map<String, String>

    /**
     * Spec-30 T3 — best-effort write-back of one canonical cover URL, keyed
     * by the SAME Work mergeKey the read path uses. Covers are curated
     * first (the curated seed pours them idempotently); untrusted device
     * write-backs are deferred until AppCheck + reporting are in place. The
     * [CoverProvenance] (source, resolvedAt) rides with the write; reads
     * ignore the fields. Idempotent by contract (a document key is replaced,
     * never duplicated); a failing write contributes nothing.
     */
    suspend fun putCover(
        mergeKey: String,
        coverUrl: String,
        provenance: CoverProvenance
    )
}

/**
 * Spec-30 T3 (#218) — the provenance of one shared canonical cover: where it
 * came from and when it was written. Written with every put; reads ignore
 * the fields, so older documents decode fine. Covers are curated/seeded
 * first — [SOURCE_CURATED] is the only origin until AppCheck + reporting
 * are in place for untrusted device write-backs.
 */
data class CoverProvenance(
    val source: String,
    val resolvedAt: Long
) {
    companion object {
        /** A cover poured from the bundled curated asset (the seed). */
        const val SOURCE_CURATED = "curated"
    }
}

/**
 * Spec-30 T3 — the honest-data sanity gate for a shared canonical cover: a
 * real http(s) URL, non-blank and below a generous length bound (the same
 * bound the profile codec uses). Enforced on every read (a wild/corrupt
 * document is a miss, never a crash) and by the curated seed's write path.
 */
object CoverSanity {

    fun isPlausible(coverUrl: String?): Boolean =
        !coverUrl.isNullOrBlank() &&
            coverUrl.length <= BookProfileLimits.MAX_URL_LEN &&
            BookProfileLimits.isHttpUrl(coverUrl)
}

/**
 * The Firestore document codec for a shared canonical cover — pure JVM so
 * the shape is unit-testable without Firebase. Document fields:
 *
 * ```
 * coverImageUrl: String  (the canonical URL — http(s), bounded)
 * source:        String  (provenance — e.g. "curated")
 * resolvedAt:    Long    (provenance — when the cover was written)
 * ```
 *
 * [fromMap] is defensive: a missing/mistyped/blank/non-http/overlong URL
 * yields null (a corrupt document is a miss, never a crash); the provenance
 * fields are ignored on read, so older documents decode fine.
 */
object CoverCodec {

    fun toMap(coverUrl: String, provenance: CoverProvenance): Map<String, Any> = mapOf(
        "coverImageUrl" to coverUrl,
        "source" to provenance.source,
        "resolvedAt" to provenance.resolvedAt
    )

    fun fromMap(map: Map<String, Any>): String? {
        val url = (map["coverImageUrl"] as? String)?.trim().orEmpty()
        return url.takeIf { CoverSanity.isPlausible(it) }
    }
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
) {
    companion object {
        /** A profile resolved from the source page by the app. */
        const val SOURCE_RESOLVED = "resolved"
    }
}

/**
 * Spec-32 T3 — a shared profile together with its resolved-at stamp, as read
 * back from the base. The stamp is what the read-skip freshness decision and
 * the fail-open fallback need.
 */
data class SharedProfileEntry(
    val profile: BookProfile,
    val resolvedAt: Long
)

/**
 * Spec-32 T3 — the freshness window of a shared profile: ~90 days. Within it
 * the profile may replace the source page fetch outright; beyond it the page
 * is re-fetched, and if that fails the stale profile is still served
 * (fail-open) rather than nothing.
 */
object ProfileFreshness {

    /** ~90 days — the short-TTL counter to spec-30's longer duration floor. */
    const val FRESHNESS_MILLIS = 90L * 24 * 60 * 60 * 1000

    fun isFresh(resolvedAt: Long, nowMillis: Long): Boolean =
        nowMillis - resolvedAt < FRESHNESS_MILLIS
}

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
 * book page yields (title, author, description, narrator, series, genres,
 * rating, cover, ordered chapters with stream URLs, total duration). What a
 * listener who already opened the book has — cached so the next one skips
 * the page fetch. Title/author are the bibliographic identity a read-skip
 * import needs to materialise the Work card without the page.
 */
data class BookProfile(
    val title: String = "",
    val author: String = "",
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
        "title" to profile.title.take(BookProfileLimits.MAX_TITLE_LEN),
        "author" to profile.author.take(BookProfileLimits.MAX_TITLE_LEN),
        "description" to profile.description.take(BookProfileLimits.MAX_DESCRIPTION_LEN),
        "narrator" to profile.narrator.take(BookProfileLimits.MAX_TITLE_LEN),
        "genres" to profile.genres.take(BookProfileLimits.MAX_GENRES).map { it.take(BookProfileLimits.MAX_TITLE_LEN) },
        "chapters" to profile.chapters
            .filter { BookProfileLimits.isHttpUrl(it.streamUrl) }
            .take(BookProfileLimits.MAX_CHAPTERS)
            .map { chapterToMap(it) }
    ) + optionalFields(profile)

    /**
     * Spec-32 T3 — decodes the profile AND its resolved-at stamp (the
     * freshness/fail-open read). A corrupt document is a miss, never a crash.
     */
    fun fromMapEntry(map: Map<String, Any>): SharedProfileEntry? {
        val profile = fromMap(map) ?: return null
        val resolvedAt = (map["resolvedAt"] as? Number)?.toLong() ?: return null
        return SharedProfileEntry(profile, resolvedAt)
    }

    fun fromMap(map: Map<String, Any>): BookProfile? {
        val rawChapters = map["chapters"] as? List<*> ?: return null
        if (rawChapters.size > BookProfileLimits.MAX_CHAPTERS) return null
        val chapters = mutableListOf<ProfileChapter>()
        for (raw in rawChapters) {
            chapters += chapterFromMap(raw as? Map<*, *> ?: continue) ?: continue
        }
        return BookProfile(
            title = (map["title"] as? String ?: "").take(BookProfileLimits.MAX_TITLE_LEN),
            author = (map["author"] as? String ?: "").take(BookProfileLimits.MAX_TITLE_LEN),
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
    /** The public Source identifier or another bounded origin label. */
    val source: String,
    val derivedAt: Long,
    /** How the value was observed; part of deterministic conflict identity. */
    val method: String = METHOD_SOURCE_METADATA
) {
    companion object {
        /** A duration derived from real source metadata (page, stream probe). */
        const val SOURCE_DERIVED = "derived"
        const val METHOD_SOURCE_METADATA = "source_metadata"
        const val METHOD_TECHNICAL_PROBE = "technical_probe"
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
 * method:         String  (how it was observed)
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
        "method" to provenance.method,
        "derivedAt" to provenance.derivedAt
    )

    fun fromMap(map: Map<String, Any>): Long? {
        val duration = map["durationSeconds"] as? Long ?: return null
        return duration.takeIf { DurationSanity.isPlausible(it) }
    }
}
