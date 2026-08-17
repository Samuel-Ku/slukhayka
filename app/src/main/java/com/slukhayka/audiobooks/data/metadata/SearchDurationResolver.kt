package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.duration.DurationBuckets
import com.slukhayka.audiobooks.data.source.GlobalSearchResult

/**
 * Spec-30 T2 (#217) — the client-first duration resolution for search cards:
 * the precedence «listener-known → shared cache (fill the gap only) → source»
 * applied to a page of [GlobalSearchResult]s, with Firestore hits mirrored
 * into the local database through the existing duration write path
 * ([AudiobookDao.updateBookStats]).
 *
 * For each card the resolver asks the local database for the Work's row (by
 * merge key). A **locally known** duration always wins and the shared cache
 * is never consulted for that card — the cache never overwrites what the
 * listener already sees (a future Metadata Override lands in the same local
 * row and inherits the same protection). Only cards with NO known local value
 * are asked of the shared store, in ONE batched read ([SharedBookMetaStore.getDurations]
 * — never a request per book). A hit fills the card, and when the matching
 * local row exists with an unknown duration it is mirrored into the database
 * via [AudiobookDao.updateBookStats] so the value works offline afterwards.
 *
 * Degrade-never by construction: a missing store, a throwing store, a corrupt
 * document or a failing database write all leave the card exactly as it was —
 * the slice never fabricates a number and never breaks the search.
 */
class SearchDurationResolver(
    private val dao: AudiobookDao,
    private val sharedStore: SharedBookMetaStore?
) {

    /**
     * Attaches [GlobalSearchResult.durationSeconds] to every card that has a
     * resolvable duration. Cards without one (no local value, no shared hit)
     * stay unchanged — the caller renders them without a duration, exactly as
     * today.
     */
    suspend fun resolve(results: List<GlobalSearchResult>): List<GlobalSearchResult> {
        val store = sharedStore ?: return results
        if (results.isEmpty()) return results

        // Local tier: one row lookup per card; a known duration wins outright.
        val localRows = results.map { result ->
            result to runCatching { localRowOf(result) }.getOrNull()
        }
        val gaps = localRows.filter { (_, row) ->
            row?.totalDurationSeconds?.let { DurationBuckets.hasKnownDuration(it) } != true
        }

        // Shared tier: ONE batched read for the gap cards only (the visible
        // books of this search page), fill-the-gap semantics.
        val hits = if (gaps.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                store.getDurations(gaps.map { (result, _) -> editionIdOf(result) })
            }.getOrDefault(emptyMap())
        }

        // Mirror: a shared hit lands in a matching local row with an unknown
        // duration through the existing write path, so it works offline.
        for ((result, row) in gaps) {
            val hit = hits[editionIdOf(result)] ?: continue
            if (row != null && !DurationBuckets.hasKnownDuration(row.totalDurationSeconds)) {
                runCatching { dao.updateBookStats(row.id, row.totalChapters, hit) }
            }
        }

        return localRows.map { (result, row) ->
            val localDuration = row?.totalDurationSeconds
                ?.takeIf { DurationBuckets.hasKnownDuration(it) }
            val duration = localDuration ?: hits[editionIdOf(result)]
            if (duration != null) result.copy(durationSeconds = duration) else result
        }
    }

    /** The local Work row behind a card, if the book is in the library. */
    private suspend fun localRowOf(result: GlobalSearchResult) =
        if (result.mergeKey.isNotBlank()) dao.findByMergeKey(result.mergeKey) else null

    /**
     * The Edition id the shared cache is keyed by: the deterministic
     * `hash(mergeKey|narrator|language)` of the card's rendition, falling
     * back to the first source url for blank-key cards.
     */
    private fun editionIdOf(result: GlobalSearchResult): String =
        EditionId.forBook(
            mergeKey = result.mergeKey,
            bookId = result.sources.firstOrNull()?.url ?: result.key,
            narrator = result.narrator
        )
}
