package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.DownloadState
import com.slukhayka.audiobooks.data.db.SourceEntity

/**
 * The one-time, idempotent startup purge of scam-source garbage: 4read's
 * clean-client audio is a 52-second artefact, never the book, and rows
 * imported before the scam fact existed must not survive.
 *
 * For a scam-only Edition the whole fake rendition goes — downloaded track
 * files, tracks, source rows, chapters, bookmarks, Listening State, the
 * facet projection and the Edition row — while the Work and the library CARD
 * stay: the book reads as an honest «аудіо недоступне», never as a
 * 52-second lie, and the replacement mapping can still find it a real source.
 * An Edition that still has a real source loses only the scam rows. Catalog
 * claims ([com.slukhayka.audiobooks.data.db.WorkSourceEntity]) of scam
 * sources are removed too.
 *
 * The scam identity lives in the registry ([SourceRegistry.isScam]); this
 * runner owns the purge decision — an Edition is scam-only when every one of
 * its source rows is a scam row, and only then is the fake rendition removed.
 * Idempotent by construction: a second run finds no scam source row and
 * reports zero.
 */
class ScamSourcePurge(
    private val dao: AudiobookDao,
    private val scamSourceIds: Set<String> = SourceRegistry.scamIds(),
    private val deleteFile: (String) -> Unit = { java.io.File(it).delete() }
) {

    /** Returns the number of scam source rows removed (0 = nothing to do). */
    suspend fun purgeOnce(): Int {
        if (scamSourceIds.isEmpty()) return 0
        val scamSources = dao.getSourcesByTypes(scamSourceIds.toList())
        if (scamSources.isEmpty()) return 0
        val scamRowIds = scamSources.map { it.id }.toSet()

        // The mirror claims of the scam source go too — discovery surfaces
        // read the mirror and must not resurrect the artifact.
        dao.deleteWorkSourcesBySourceIds(scamSourceIds.toList())

        val emptiedBookIds = mutableSetOf<String>()
        val editionIds = scamSources
            .mapNotNull { it.editionId?.takeIf(String::isNotBlank) }
            .distinct()
        for (editionId in editionIds) {
            val editionSources = dao.getSourcesForEditionSync(editionId)
            val scamRows = editionSources.filter { it.id in scamRowIds }
            if (scamRows.isEmpty()) continue
            if (editionSources.size == scamRows.size) {
                // Scam-only rendition: the fake Edition goes whole.
                emptiedBookIds += editionSources.map { it.bookId }
                for (row in scamRows) removeSourceWithFiles(row)
                dao.deleteChaptersForEdition(editionId)
                dao.deleteBookmarksForEdition(editionId)
                dao.deletePlaybackProgressForEdition(editionId)
                dao.deleteEditionFacet(editionId)
                dao.deleteEditionById(editionId)
            } else {
                // A real source remains: only the scam rows go.
                for (row in scamRows) removeSourceWithFiles(row)
            }
        }
        // Legacy scam rows without an Edition: the source alone goes.
        for (row in scamSources.filter { it.editionId.isNullOrBlank() }) {
            removeSourceWithFiles(row)
        }

        // The card stays (honest unavailable), but its fake page URL, totals
        // and download state are reset — once per card left without source.
        for (bookId in emptiedBookIds) {
            if (dao.getSourcesForBookSync(bookId).isNotEmpty()) continue
            dao.updateBookStats(bookId, 0, 0)
            dao.updateBookSourceUrl(bookId, "")
            dao.updateDownloadStateWithState(
                bookId,
                isDownloaded = false,
                progress = 0f,
                state = DownloadState.IDLE
            )
        }
        return scamSources.size
    }

    /** Removes one source row with its downloaded files and track rows. */
    private suspend fun removeSourceWithFiles(source: SourceEntity) {
        for (track in dao.getTracksForSourceSync(source.id)) {
            track.localFilePath?.takeIf(String::isNotBlank)?.let(deleteFile)
        }
        dao.deleteTracksForSource(source.id)
        dao.deleteSourceById(source.id)
    }
}
