package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.BookRow
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
 * Above that local tier sits the listener's own decision
 * ([CoverOverride], ADR-0053 / #855 T2): a pinned cover is SHOWN on the card,
 * and a pinned ABSENCE is shown as an honest «no cover» — in both cases the
 * shared cache and the source's own claim are never allowed to step in.
 * Only cards whose local row has NO cover and no Override are asked of the
 * shared store, in ONE batched read ([SharedBookMetaStore.getCovers] — never
 * a request per Work). A hit fills the card, and when the matching local row
 * exists with a blank cover it is mirrored into the database so the canonical
 * URL works offline (US-5/US-9 — the cover survives the source URL dying). A
 * card without a Work identity (blank merge key) is never consulted — the
 * shared base has nothing keyed by it. The source's own claim on the card is
 * the last resort: it shows when nothing higher resolved.
 *
 * Degrade-never by construction: a missing store, a throwing store, a
 * corrupt document or a failing database write all leave the card exactly as
 * it was — the slice never fabricates a URL and never breaks the search. A
 * failing Override read degrades to «no decision», and that path writes
 * nothing at all.
 */
class SearchCoverResolver(
    private val dao: AudiobookDao,
    private val sharedStore: SharedBookMetaStore?
) {

    /** The local row behind one card plus the listener's cover decision. */
    private data class Local(val row: BookRow?, val pinned: CoverOverride.Pinned?)

    /**
     * Attaches the canonical [GlobalSearchResult.coverImageUrl] to every card
     * that has one resolvable from a higher tier than the source's claim.
     * Cards without one stay unchanged — the caller renders them without a
     * cover, exactly as today.
     */
    suspend fun resolve(results: List<GlobalSearchResult>): List<GlobalSearchResult> {
        val store = sharedStore ?: return results
        if (results.isEmpty()) return results

        // Local tier: one row + one Override lookup per card; a known cover or
        // a listener decision wins outright.
        val localRows = results.map { result ->
            result to runCatching { localOf(result) }.getOrNull()
        }
        val gaps = localRows.filter { (result, local) ->
            // #855 (T2) — an Override is the strongest tier: a pinned cover is
            // already shown, a pinned absence stays absent, and neither the
            // shared base nor the source's claim may fill either.
            local?.pinned == null &&
                // A blank-merge-key card has no Work identity — it can never
                // hit the shared base, so it is not a gap (its own claim stands).
                local?.row?.coverImageUrl.isNullOrBlank() &&
                result.mergeKey.isNotBlank()
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
        for ((result, local) in gaps) {
            val hit = hits[result.mergeKey] ?: continue
            val row = local?.row ?: continue
            if (row.coverImageUrl.isNullOrBlank()) {
                runCatching { dao.updateCoverImageUrl(row.id, hit) }
            }
        }

        return localRows.map { (result, local) ->
            val cover = CoverOverride.over(
                pinned = local?.pinned,
                local = local?.row?.coverImageUrl,
                claimed = hits[result.mergeKey] ?: result.coverImageUrl
            )
            if (cover != result.coverImageUrl) result.copy(coverImageUrl = cover) else result
        }
    }

    /** The local Work row and the listener's decision behind one card. */
    private suspend fun localOf(result: GlobalSearchResult): Local {
        if (result.mergeKey.isBlank()) return Local(row = null, pinned = null)
        return Local(
            row = dao.findByMergeKey(result.mergeKey),
            pinned = CoverOverrideStore(dao).pinned(result.mergeKey)
        )
    }
}