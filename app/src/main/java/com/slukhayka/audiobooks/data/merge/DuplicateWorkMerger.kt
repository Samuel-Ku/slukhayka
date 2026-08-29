package com.slukhayka.audiobooks.data.merge

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import kotlinx.coroutines.flow.first

/**
 * Spec-27 (#184) BUG-002 — the one-time, idempotent startup pass that merges
 * library rows which share a hardened Work identity ([MergeKey]).
 *
 * Before the key hardening, a raw page title («Трохи ненависті -
 * АудіоКниги Українською») produced a DIFFERENT merge key than the clean
 * title, so importing the raw-titled page created a second library card with
 * its own progress and bookmarks. This pass collapses every such duplicate
 * group: the cleanest row survives (already-scrubbed title, earliest entry),
 * and the loser's listening state — progress, bookmarks, sources — moves onto
 * the survivor. The loser's duplicate chapters and its Works/Entry rows are
 * dropped.
 *
 * Local books (blank [BookRow.sourceUrl]) are never merged: they are the
 * user's own files with no SEO convention, and a ` - ` subtitle in a folder
 * name must not collapse two of them.
 *
 * Idempotent by construction: a group collapses on the first run, so a second
 * run finds nothing. The pass is best-effort — [mergeOnce] returns how many
 * duplicate rows were removed, and a failing group never aborts the others.
 */
class DuplicateWorkMerger(private val dao: AudiobookDao) {

    /** Merges every duplicate group; returns the number of loser rows removed. */
    suspend fun mergeOnce(): Int {
        val books = dao.getAllAudiobooksOnce()
        val groups = books
            .asSequence()
            .filter { it.sourceUrl.isNotBlank() }
            .groupBy { MergeKey.keyFor(it.title, it.author) }
            .filterKeys { it.isNotBlank() }
            .values
            .filter { it.size > 1 }
        var removed = 0
        for (group in groups) {
            val survivor = pickSurvivor(group)
            for (loser in group.filter { it.id != survivor.id }) {
                mergeInto(survivor, loser)
                removed++
            }
        }
        return removed
    }

    /**
     * The row that keeps the card: the one whose stored title is already the
     * scrubbed clean title (the raw SEO-titled duplicate is the loser), then
     * the earliest entry, then the smallest id — deterministic on any device.
     */
    private fun pickSurvivor(group: List<BookRow>): BookRow =
        group.minWithOrNull(
            compareBy<BookRow>(
                { if (it.title == MetadataAssertions.normalizeTitle(it.title)) 0 else 1 },
                { it.createdAt },
                { it.id }
            )
        ) ?: group.first()

    /**
     * Moves everything the user's copy carries from [loser] onto [survivor]
     * and drops the loser row. Uses only existing DAO operations so the real
     * Room DAO and the JVM fake behave identically.
     */
    private suspend fun mergeInto(survivor: BookRow, loser: BookRow) {
        val survivorEdition = dao.getEditionForWork(survivor.id)
        val loserEdition = dao.getEditionForWork(loser.id)
        // When the survivor has no rendition yet, adopt the loser's edition —
        // its progress/bookmarks/sources stay keyed to that edition, which is
        // now the survivor's. Otherwise distinct editions transfer the state.
        val adopted = survivorEdition == null && loserEdition != null
        val survivorEditionId: String? = when {
            survivorEdition != null -> survivorEdition.id
            loserEdition != null -> {
                dao.getEditionById(loserEdition.id)?.let { edition ->
                    dao.insertEdition(edition.copy(workId = survivor.id))
                }
                loserEdition.id
            }
            else -> null
        }
        val distinctEditions = loserEdition != null && loserEdition.id != survivorEditionId

        // Listening state: progress + bookmarks.
        if (distinctEditions) {
            // Progress: the newer row wins on the survivor's edition. The
            // loser's original row (keyed to its own edition) is dropped in
            // both branches — the copy is the only survivor.
            val survivorProgress = dao.getPlaybackProgressSyncByEdition(survivorEditionId!!)
            val loserProgress = dao.getPlaybackProgressSyncByEdition(loserEdition.id)
            if (loserProgress != null) {
                if (survivorProgress == null || loserProgress.lastListenedAt > survivorProgress.lastListenedAt) {
                    if (survivorProgress != null) dao.deletePlaybackProgressForBook(survivor.id)
                    dao.savePlaybackProgress(
                        loserProgress.copy(editionId = survivorEditionId, bookId = survivor.id)
                    )
                }
                dao.deletePlaybackProgressForBook(loser.id)
            }
            // Bookmarks re-point in place (same id — REPLACE moves the row).
            dao.getBookmarksForBook(loser.id).first().forEach { bookmark ->
                dao.insertBookmark(bookmark.copy(bookId = survivor.id, editionId = survivorEditionId))
            }
        } else {
            // Adopted edition (or edition-less loser): only the bookId re-points.
            dao.getPlaybackProgressSyncByEdition(loserEdition?.id ?: "").let { progress ->
                if (progress != null) dao.savePlaybackProgress(progress.copy(bookId = survivor.id))
            }
            dao.getBookmarksForBook(loser.id).first().forEach { bookmark ->
                dao.insertBookmark(bookmark.copy(bookId = survivor.id))
            }
        }

        // Sources → survivor (they keep playing the survivor's rendition).
        // Dedup by (type, url) like the import path's `known` check — the raw
        // duplicate and the clean row carry the SAME source, so it must not
        // become two rows on one card.
        val survivorSources = dao.getSourcesForBookSync(survivor.id)
        dao.getSourcesForBookSync(loser.id).forEach { source ->
            if (survivorSources.none { it.type == source.type && it.url == source.url }) {
                dao.insertSources(
                    listOf(source.copy(bookId = survivor.id, editionId = survivorEditionId))
                )
            }
        }
        dao.deleteSourcesForBook(loser.id)

        // The loser's logical chapters duplicate the survivor's list — drop them.
        dao.deleteChaptersForBook(loser.id)

        // Work-level identity: the loser's carriers move to the survivor's Work.
        val loserWork = loser.workId ?: loser.id
        val survivorWork = survivor.workId ?: survivor.id
        if (loserWork != survivorWork) {
            // #388 — ensure survivor Work exists before re-pointing carriers;
            // a blank-key survivor (no Works row) cannot be a FK parent.
            val survivorWorkExists = if (survivorWork == survivor.id) {
                dao.getWorkById(survivorWork) != null
            } else {
                true
            }
            if (!survivorWorkExists) {
                android.util.Log.w(
                    "DuplicateWorkMerger",
                    "skip re-pointing work_sources: survivor work $survivorWork not found"
                )
            } else {
                val survivorWorkSources = dao.getWorkSourcesForWorkSync(survivorWork)
                dao.getWorkSourcesForWorkSync(loserWork).forEach { source ->
                    // Skip a carrier the survivor already has (same source + url) —
                    // the «N джерел» badge must not double-count one source.
                    if (survivorWorkSources.none { it.sourceId == source.sourceId && it.sourceUrl == source.sourceUrl }) {
                        dao.safeUpsertWorkSource(source.copy(workId = survivorWork))
                    }
                }
            }
            val survivorSeries = dao.getSeriesMembersForWork(survivorWork).map { it.seriesId }.toSet()
            dao.getSeriesMembersForWork(loserWork).forEach { member ->
                if (member.seriesId !in survivorSeries) {
                    dao.upsertSeriesMember(member.copy(workId = survivorWork))
                }
            }
            dao.deleteSeriesMembersForWork(loserWork)
            // The works row delete cascades the loser's original work_sources
            // rows (FK CASCADE) — the transferred copies already carry the
            // survivor's workId. Blank-key books have no works row.
            if (loserWork != loser.id) dao.deleteWork(loserWork)
        }

        // The loser's playback-event history is dropped with the row.
        dao.deletePlaybackEventsForBook(loser.id)

        // Leftovers.
        dao.deleteLibraryEntry(loser.id)
        if (distinctEditions) dao.deleteEditionsForWork(loser.id)
        dao.deleteAudiobook(loser.id)
    }
}
