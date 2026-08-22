package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.db.AudiobookDao

/**
 * Spec-24 T1 + #264 — the one-time, idempotent startup pass that scrubs
 * stored metadata written before the write-path rules existed: SEO title
 * suffixes from titles (audiobooks + works rows, [MetadataAssertions.normalizeTitle])
 * and SEO description templates from the audiobooks descriptions
 * ([MetadataAssertions.normalizeDescription]). The pure rules live in
 * [MetadataAssertions]; this is only the thin runner over the DAO. Idempotent
 * by construction: each rule applied twice matches nothing, so a second run
 * reports zero changes.
 */
class StoredMetadataScrub(private val dao: AudiobookDao) {

    /**
     * Rewrites stored titles and descriptions through the pure rules;
     * returns the rows changed. A description that scrubs to empty is
     * WRITTEN as empty — an unknown annotation renders as absent (ADR-0014),
     * never a fabricated one.
     */
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
        for (row in dao.getAllBookDescriptionRows()) {
            val scrubbed = MetadataAssertions.normalizeDescription(row.description)
            if (scrubbed != row.description) {
                dao.updateBookDescription(row.id, scrubbed)
                changed++
            }
        }
        return changed
    }
}
