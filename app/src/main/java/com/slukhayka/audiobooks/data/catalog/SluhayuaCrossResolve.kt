package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.source.SourceBook

/**
 * Spec #462 ID7 (#469) — the tap-time cross-resolve of a 4read-only catalog
 * card onto the direct sluhayua source.
 *
 * Since ADR-0037 (spec-49 T2) this is a thin delegate behind the general
 * replacement mapping ([SourceReplacementMapping]): the sluhayua-only door
 * was the first case of the rule — every direct source is the rule now. The
 * contract is unchanged: at most one live search request per call, none
 * while a fresh memo or shared-cache verdict exists, MergeKey as the whole
 * match rule, best-effort and silent by contract — a failing search, a
 * failing store or a corrupt document all degrade to «no match».
 */
class SluhayuaCrossResolve(
    /** The sluhayua search call — one invocation equals one HTTP request. */
    private val search: suspend (query: String) -> List<SourceBook>,
    private val cache: com.slukhayka.audiobooks.data.search.SearchCache? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** The matched direct sluhayua card: everything the import needs. */
    data class Match(
        val url: String,
        val title: String,
        val author: String,
        val narrator: String,
        val coverImageUrl: String?
    )

    private val delegate = SourceReplacementMapping(
        directSearches = mapOf("sluhayua" to search),
        union = { emptyList() },
        cache = cache,
        clock = clock
    )

    /**
     * The direct sluhayua match for the Work, or null. Issues at most one
     * live search request per call — and none at all while a fresh memo or
     * shared-cache verdict exists.
     */
    suspend fun resolve(title: String, author: String, mergeKey: String): Match? {
        val match = delegate.resolve(title, author, mergeKey) ?: return null
        return Match(
            url = match.url,
            title = match.title,
            author = match.author,
            narrator = match.narrator,
            coverImageUrl = match.coverImageUrl
        )
    }
}
