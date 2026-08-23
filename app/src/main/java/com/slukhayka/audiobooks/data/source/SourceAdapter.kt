package com.slukhayka.audiobooks.data.source

/**
 * Spec-10 T3 — the adapter seam of the multi-source catalog.
 *
 * One [SourceAdapter] per playable source (4read, sound-books.net,
 * audiobook-mp3.com/uk, lihtar.in.ua, …). Each adapter owns its parsing, so a
 * markup change in one source fails only that source's fixture tests (parser
 * seam). Outputs are normalized [SourceBook] / [SourceBookDetail] models; the
 * repository turns them into Work rows (with [com.slukhayka.audiobooks.data.merge.MergeKey]
 * dedup) and Source rows.
 */

/** A book card as any source catalogue/search returns it. */
data class SourceBook(
    val title: String,
    val author: String,
    val narrator: String = "",
    val url: String,
    val coverImageUrl: String? = null,
    val seriesTitle: String? = null,
    val seriesIndex: Int? = null,
    val genre: String = "",
    val totalDurationSeconds: Long = 0L,
    val sourceId: String
)

/** One chapter of a book as parsed from a source book page. */
data class SourceChapter(
    val title: String,
    val streamUrl: String,
    val durationSeconds: Long = 0L
)

/** A series (cycle) reference parsed from a source book page. */
data class SeriesRef(
    val name: String,
    /** Volume number when the page carries one; null when absent (never 0). */
    val position: Int? = null,
    val url: String? = null
)

/** One related-book card from a source page's "you may also like" section. */
data class RelatedBook(
    val title: String,
    val author: String = "",
    val url: String,
    val coverImageUrl: String? = null
)

/**
 * Full parse of a source book page (spec-14 T1): cover, ordered chapters and
 * the enriched profile — rating, genres, series, related books. Fields the
 * page does not provide are absent (null/empty), never fabricated.
 */
data class SourceBookDetail(
    val title: String,
    val author: String,
    val narrator: String = "",
    val url: String,
    val coverImageUrl: String? = null,
    val chapters: List<SourceChapter>,
    /** Real total duration from the page ("Триває:" / schema.org), null when absent. */
    val totalDurationSeconds: Long? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val series: SeriesRef? = null,
    val related: List<RelatedBook> = emptyList(),
    /**
     * Spec-15 T5 — the page's own description blurb (og:description), for the
     * per-source detail blocks. Absent when the page carries none (or when the
     * og:description is not a description — lihtar renders its AUTHOR there,
     * so lihtar stays empty). Never fabricated.
     */
    val description: String = ""
)

/**
 * A playable source of the aggregator. `sourceId` is the stable id stored in
 * the `sources` table (spec-10 T1: `4read`, `soundbooks`, `audiobookmp3`,
 * `lihtar`, …).
 */
interface SourceAdapter {
    val sourceId: String

    /**
     * Best-effort site search. Sources without a usable search endpoint (or
     * whose search is robots-discouraged) return an empty list; discovery then
     * happens through [fetchNew] and category enumeration.
     */
    suspend fun search(query: String): List<SourceBook>

    /** Parses a book page: title/author/cover and ordered chapter stream URLs. */
    suspend fun fetchBookPage(url: String): SourceBookDetail

    /** Recent additions of the source — the «Нове з кожного джерела» feed (T5). */
    suspend fun fetchNew(limit: Int = 20): List<SourceBook>

    /**
     * Spec-15 T1 — catalogue enumeration: a broad sample of the source's full
     * catalogue (category/genre pages where the source has them), feeding the
     * unified «Увесь каталог» union on Огляд. Defaults to [fetchNew] so
     * sources without dedicated category enumeration still participate; 4read
     * is not part of the union (its catalogue is natively browsed — spec #8
     * sections).
     */
    suspend fun fetchCatalog(limit: Int = 40): List<SourceBook> = fetchNew(limit)

    /**
     * WebView-pattern sources (spec-13): discovery only works through the
     * live browser session (Cloudflare). The feed pipeline turns an
     * absent/stale session into a «відкрити джерело, щоб оновити» CTA row
     * instead of silently dropping the source.
     */
    val sessionBound: Boolean get() = false

    /**
     * ADR-0006 — captured-page import capability: builds the book detail from
     * HTML captured in the live WebView session (past the Cloudflare
     * challenge), resolving playlist/iframe content through the adapter's own
     * transport. The default is "not mine" — null — so only WebView-pattern
     * sources support the door and NO import door has to downcast to a
     * concrete adapter: a future WebView-pattern source works through the
     * same door with no changes outside its adapter. One name across
     * adapters (4read + sluhay override it); null also covers an unparseable
     * page — the doors surface "nothing playable".
     */
    suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail? = null

    /**
     * Spec-40 (#282) — visitors' comments of the source book page, in page
     * order. The default is "none" — empty — so sources without provable
     * comments cost nothing and no consumer has to downcast to a concrete
     * adapter (the [parseCapturedPage] pattern). Only a source whose live
     * pages demonstrably carry comments overrides it; the texts are plain
     * trimmed strings bounded by each override's own limits.
     */
    suspend fun parseComments(html: String): List<String> = emptyList()

    /**
     * The stable Work id for [url], produced in exactly this one place
     * (spec-14 T5): no import door derives ids itself. The default is the
     * generic "<sourceId>-<slug>" scheme; sources with a catalogue slug
     * scheme (4read → "4read-slug") override it.
     */
    fun bookId(url: String): String {
        val slug = url.substringAfterLast('/').substringBefore('?')
            .removeSuffix(".html")
            .removeSuffix(".m3u")
            .ifBlank { "book-${System.currentTimeMillis()}" }
        return "$sourceId-$slug"
    }
}
