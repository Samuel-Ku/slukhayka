package com.example.data.source

/**
 * Spec-10 T3 — the adapter seam of the multi-source catalog.
 *
 * One [SourceAdapter] per playable source (4read, sound-books.net,
 * audiobook-mp3.com/uk, lihtar.in.ua, …). Each adapter owns its parsing, so a
 * markup change in one source fails only that source's fixture tests (parser
 * seam). Outputs are normalized [SourceBook] / [SourceBookDetail] models; the
 * repository turns them into Work rows (with [com.example.data.merge.MergeKey]
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

/** Full parse of a source book page: cover + ordered chapters. */
data class SourceBookDetail(
    val title: String,
    val author: String,
    val narrator: String = "",
    val url: String,
    val coverImageUrl: String? = null,
    val chapters: List<SourceChapter>
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
}
