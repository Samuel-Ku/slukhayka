package com.example.data.metadata

import com.example.data.db.AudiobookDao

/**
 * Spec-24 T1 — the one-time, idempotent startup pass that scrubs SEO title
 * suffixes from titles already stored before the write-path rule existed
 * (audiobooks + works rows). The pure rule lives in
 * [MetadataAssertions.normalizeTitle]; this is only the thin runner over the
 * DAO. Idempotent by construction: normalizeTitle applied twice matches
 * nothing, so a second run reports zero changes.
 */
class StoredTitleScrub(private val dao: AudiobookDao) {

    /** Rewrites stored titles through [MetadataAssertions.normalizeTitle]; returns the rows changed. */
    suspend fun scrubOnce(): Int {
        var changed = 0
        for (row in dao.getAllBookTitleRows()) {
            val scrubbed = MetadataAssertions.normalizeTitle(row.title)
            if (scrubbed != row.title) {
                dao.updateBookTitle(row.id, scrubbed)
                changed++
            }
        }
        for (row in dao.getAllWorkTitleRows()) {
            val scrubbed = MetadataAssertions.normalizeTitle(row.title)
            if (scrubbed != row.title) {
                dao.updateWorkTitle(row.id, scrubbed)
                changed++
            }
        }
        return changed
    }
}
