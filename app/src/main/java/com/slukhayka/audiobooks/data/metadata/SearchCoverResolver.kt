package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.source.GlobalSearchResult

/**
 * Spec-30 T3 (#218) — the client-first cover resolution for search cards:
 * the precedence «locally known cover → shared cache (fill the gap only) →
 * the source's own claim» applied to a page of [GlobalSearchResult]s, with
 * Firestore hits mirrored into the local database through the existing cover
 * write path ([AudiobookDao.updateCoverImageUrl]).
 *
 * For each card the resolver asks the local database for the Work's row (by
 * merge key). A **locally known cover** — a mirrored canonical URL or an
 * imported one — always wins and the shared cache is never consulted for
 * that card: the cache never overwrites what the listener already sees.
 * Only cards whose local row has NO cover are asked of the shared store, in
 * ONE batched read ([SharedBookMetaStore.getCovers] — never a request per
 * Work). A hit fills the card, and when the matching local row exists with a
 * blank cover it is mirrored into the database so the canonical URL works
 * offline (US-5/US-9 — the cover survives the source URL dying). A card
 * without a Work identity (blank merge key) is never consulted — the shared
 * base has nothing keyed by it. The source's own claim on the card is the
 * last resort: it shows when nothing higher resolved.
 *
 * Degrade-never by construction: a missing store, a throwing store, a
 * corrupt document or a failing database write all leave the card exactly as
 * it was — the slice never fabricates a URL and never breaks the search.
 */
class SearchCoverResolver(
    private val dao: AudiobookDao,
    private val sharedStore: SharedBookMetaStore?
) {

    /**
     * Attaches the canonical [GlobalSearchResult.coverImageUrl] to every card
     * that has one resolvable from a higher tier than the source's claim.
     * Cards without one stay unchanged — the caller renders them without a
     * cover, exactly as today.
     */
    suspend fun resolve(results: List<GlobalSearchResult>): List<GlobalSearchResult> {
        val store = sharedStore ?: return results
        if (results.isEmpty()) return results

        // Local tier: one row lookup per card; a known cover wins outright.
        val localRows = results.map { result ->
            result to runCatching { localRowOf(result) }.getOrNull()
        }
        val gaps = localRows.filter { (result, row) ->
            // A blank-merge-key card has no Work identity — it can never hit
            // the shared base, so it is not a gap (its own claim stands).
            row?.coverImageUrl.isNullOrBlank() && result.mergeKey.isNotBlank()
        }

        // Shared tier: ONE batched read for the gap cards only (the visible
        // books of this search page), fill-the-gap semantics.
        val hits = if (gaps.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                store.getCovers(gaps.map { (result, _) -> result.mergeKey })
            }.getOrDefault(emptyMap())
        }

        // Mirror: a shared hit lands in a matching blank-cover row through
        // the existing write path, so the canonical URL works offline.
        for ((result, row) in gaps) {
            val hit = hits[result.mergeKey] ?: continue
            if (row != null && row.coverImageUrl.isNullOrBlank()) {
                runCatching { dao.updateCoverImageUrl(row.id, hit) }
            }
        }

        return localRows.map { (result, row) ->
            val localCover = row?.coverImageUrl?.takeIf { it.isNotBlank() }
            val cover = localCover ?: hits[result.mergeKey] ?: result.coverImageUrl
            if (cover != result.coverImageUrl) result.copy(coverImageUrl = cover) else result
        }
    }

    /** The local Work row behind a card, if the book is in the library. */
    private suspend fun localRowOf(result: GlobalSearchResult) =
        if (result.mergeKey.isNotBlank()) dao.findByMergeKey(result.mergeKey) else null
}