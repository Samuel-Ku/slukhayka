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
 * Degrade-never by construction: no store, a throwing store, a corrupt
 * document or a failing write all leave the rows exactly as they are and the
 * pass returns what it actually filled (0 on any failure).
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

        val hits = runCatching {
            store.getCovers(rows.mapNotNull { it.mergeKey })
        }.getOrDefault(emptyMap())

        var filled = 0
        for (row in rows) {
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