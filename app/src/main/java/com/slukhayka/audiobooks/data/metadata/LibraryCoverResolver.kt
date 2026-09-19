package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.db.AudiobookDao

/**
 * Spec-30 T3 (#218) — the library half of the client-first cover resolution:
 * fills the Медіатека rows that have NO local cover from the shared
 * canonical base, so the canonical URL shows in the library without any
 * search (US-2/US-5 — richer metadata without action, offline afterwards).
 *
 * The pass reads the rows the database itself marks as coverless
 * ([AudiobookDao.getLibraryRowsMissingCovers] — blank cover AND a Work
 * identity), asks the shared store for them in ONE batched read (never a
 * request per Work), and mirrors every hit through the EXISTING cover write
 * path ([AudiobookDao.updateCoverImageUrl]) — the same door the search
 * resolver and the import paths use, so the row's known cover can never be
 * clobbered: a row with a cover is simply never a candidate.
 *
 * ADR-0053 / #855 (T2) adds the stronger tier above both: the listener's own
 * Override ([CoverOverride]). A Work the listener decided about is never a
 * candidate — a pinned cover is already on the row, and a pinned ABSENCE must
 * stay absent instead of being filled back in on the next pass.
 *
 * Degrade-never by construction: no store, a throwing store, a corrupt
 * document or a failing write all leave the rows exactly as they are and the
 * pass returns what it actually filled (0 on any failure). A failing
 * Override read counts as «decided»: it never licenses a write.
 */
class LibraryCoverResolver(
    private val dao: AudiobookDao,
    private val sharedStore: SharedBookMetaStore?
) {

    /**
     * Fills up to [limit] coverless library rows from the shared base.
     * Returns how many rows were actually filled (0 on a miss, a failure or
     * when there is nothing to fill).
     */
    suspend fun resolve(limit: Int = MAX_ROWS): Int {
        val store = sharedStore ?: return 0
        val rows = runCatching { dao.getLibraryRowsMissingCovers(limit) }.getOrNull() ?: return 0
        if (rows.isEmpty()) return 0

        // #855 (T2) — the Override is consulted BEFORE the shared base, and it
        // is consulted per row (the same read budget the search mirror already
        // spends on the local row).
        val overrides = CoverOverrideStore(dao)
        val candidates = rows.filterNot { row ->
            val key = row.mergeKey?.takeIf { it.isNotBlank() } ?: return@filterNot false
            // A failing Override read counts as «decided»: degrade-never, so it
            // never licenses a write the listener may have forbidden.
            runCatching { overrides.pinned(key) != null }.getOrDefault(true)
        }
        if (candidates.isEmpty()) return 0

        val hits = runCatching {
            store.getCovers(candidates.mapNotNull { it.mergeKey })
        }.getOrDefault(emptyMap())

        var filled = 0
        for (row in candidates) {
            val hit = hits[row.mergeKey] ?: continue
            val wrote = runCatching { dao.updateCoverImageUrl(row.id, hit) }.isSuccess
            if (wrote) filled++
        }
        return filled
    }

    companion object {
        /**
         * The default batch bound: the visible library of one screen plus a
         * little headroom — never the whole base (the free-tier operating
         * boundary, spec-30).
         */
        const val MAX_ROWS = 60
    }
}