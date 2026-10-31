package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.source.decodeEntities

/**
 * Spec-24 T1 + #264 + #964 — the one-time, idempotent startup pass that scrubs
 * stored metadata written before the write-path rules existed: SEO title
 * suffixes from titles (audiobooks + works rows, [MetadataAssertions.normalizeTitle]),
 * SEO description templates from the audiobooks descriptions
 * ([MetadataAssertions.normalizeDescription]), and the source's rendered HTML
 * entity from stored person names (audiobooks author/narrator, works.author,
 * editions.narrator) through the shared
 * [com.slukhayka.audiobooks.data.source.decodeEntities]. The pure rules live in
 * [MetadataAssertions]; this is only the thin runner over the DAO. Idempotent
 * in ONE run: the name pass goes first (#1684), so the title pass already sees
 * the decoded author; each rule applied twice matches nothing and a second
 * startup reports zero changes.
 *
 * The name repair rewrites TEXT only. `works.mergeKey` and `editions.id` (and
 * the author/edition facet ids derived from a name) keep the value they were
 * stored with — reconciling those identities is the separate #968 decision,
 * never a silent re-key behind the listener's back.
 */
class StoredMetadataScrub(private val dao: AudiobookDao) {

    /**
     * Rewrites stored titles, descriptions and person names through the pure
     * rules; returns the rows changed. A description that scrubs to empty is
     * WRITTEN as empty — an unknown annotation renders as absent (ADR-0014),
     * never a fabricated one.
     */
    suspend fun scrubOnce(): Int {
        var changed = 0
        // #1684 — the NAME pass runs FIRST, before the title pass. The
        // Soundbooks wrapper rule strips a trailing « - <author>» suffix by
        // comparing the stored author with the title; with the name still
        // encoded («Дев&#x27;ятко») the comparison missed and the suffix survived
        // the first run, so the same row needed a SECOND startup to settle.
        // Decoding names first makes the whole pass idempotent in one run.
        //
        // A person name stored before the write path decoded it is repaired by
        // the SAME shared decoder the write-path seam uses. Only the entity
        // decode runs here — the seam's brand scrub is not re-applied to
        // stored rows, so the repair never blanks a name it was not asked to
        // touch. A clean name and an unknown/malformed entity are
        // byte-identical (the decoder never fabricates a character).
        //
        // Named boundary: [decodeEntities] is SINGLE-pass, so a nested entity
        // (&amp;#x27;) written before the write-path rules decodes one layer per
        // startup run — the same accepted limit the title repair has had since
        // #964, not a new guarantee.
        for (row in dao.getAllBookNameRows()) {
            val author = decodeEntities(row.author)
            val narrator = decodeEntities(row.narrator)
            if (author != row.author || narrator != row.narrator) {
                dao.updateBookNames(row.id, author, narrator)
                changed++
            }
        }
        for (row in dao.getAllWorkAuthorRows()) {
            val author = decodeEntities(row.name)
            if (author != row.name) {
                dao.updateWorkAuthor(row.id, author)
                changed++
            }
        }
        for (row in dao.getAllEditionNarratorRows()) {
            val narrator = decodeEntities(row.name)
            if (narrator != row.name) {
                dao.updateEditionNarrator(row.id, narrator)
                changed++
            }
        }
        for (row in dao.getAllBookTitleRows()) {
            val scrubbed = MetadataAssertions.normalizeTitle(row.title, row.author)
            if (scrubbed != row.title) {
                dao.updateBookTitle(row.id, scrubbed)
                changed++
            }
        }
        for (row in dao.getAllWorkTitleRows()) {
            val scrubbed = MetadataAssertions.normalizeTitle(row.title, row.author)
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
