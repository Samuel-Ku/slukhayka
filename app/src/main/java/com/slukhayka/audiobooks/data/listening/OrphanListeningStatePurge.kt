package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.data.db.AudiobookDao

/**
 * #1101 — the repair pass for rows whose book no longer exists.
 *
 * The raw purge in the v32→v42 migrations (`DELETE FROM audiobooks WHERE
 * sourceUrl LIKE '%4read.org%' …`, nine migrations, all registered) removed
 * books without their dependants. `bookmarks`, Listening State
 * (`playback_progress`) and the event trail (`playback_events`) carry no
 * foreign key, so those rows stayed — and stay — on every device that crossed
 * v32→v42. The visible half is a «Збережене» row labelled «Аудіокнига» that
 * does nothing when tapped (#1081, reproduced on the device); the listening
 * half is invisible only because reads go by id.
 *
 * The repair lives here, at startup, rather than in another migration,
 * because a migration would heal only that one crossing: any future raw
 * DELETE, any deleted row that slipped past its cascade, would leave the same
 * garbage again. This pass is idempotent — it only ever looks at rows whose
 * `bookId` resolves to no book — so it removes the old damage and keeps
 * removing new damage of the same shape.
 *
 * Two rules hold the line on what it may touch:
 *
 * 1. **Only unresolvable rows.** A bookmark, a progress row or an event whose
 *    book exists is never a candidate, whatever its `editionId` says. A
 *    bookmark whose book is alive and whose Edition is gone is a different
 *    defect with a different owner (see `ScamSourcePurge`), and guessing at it
 *    here would silently discard a listener's bookmark.
 * 2. **Only the three tables above.** Books, Works, Sources, Library Entries
 *    and any live state are none of this pass's business — the purge that
 *    caused the damage is over, and nothing here re-runs it.
 *
 * A `LIKE` on a book id could not express rule 1: a missing book is a
 * non-existent id, and only the subquery says so exactly.
 */
class OrphanListeningStatePurge(private val dao: AudiobookDao) {

    /** Removes every dependent row whose book is gone. Idempotent; returns rows removed. */
    suspend fun purgeOnce(): Int {
        var removed = 0
        removed += dao.deleteOrphanBookmarks()
        removed += dao.deleteOrphanPlaybackProgress()
        removed += dao.deleteOrphanPlaybackEvents()
        return removed
    }

    /** The rows this pass would remove — the honest count for a log or a test. */
    suspend fun countOrphans(): Int =
        dao.countOrphanBookmarks() +
            dao.countOrphanPlaybackProgress() +
            dao.countOrphanPlaybackEvents()
}
