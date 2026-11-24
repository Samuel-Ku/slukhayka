package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.streamOnlyFor

/**
 * ADR-0004 + ADR-0007 — the ONE write module that turns a Source adapter's
 * resolved page into stored Edition / Chapter / SourceTrack rows.
 *
 * Why a module and not two doors. The explicit import ([LibraryImport]) and
 * the on-open preparation of a chapter-less Edition
 * ([com.slukhayka.audiobooks.data.catalog.SourceCatalog.getPlayableChapters])
 * wrote the same graph twice, and the second write hard-coded the Source type
 * «4read»: a page from a foreign allowed Direct Source was stored under the
 * wrong provenance, so its tracks stopped pairing and its access facts were
 * the 4read ones. Here the Source's identity, type, url and stream-only rule
 * all derive from the adapter's own [sourceId] ([streamOnlyFor] reads the
 * ADR-0038 facts for that id) — no door spells a source literal.
 *
 * Scope is the materialization of ONE resolved page, not a rebuild of the
 * Source Catalog. Pure claim normalization stays in [MetadataAssertions];
 * this module owns persistence and the structural rules:
 *  - the Edition's logical Chapter list belongs to the FIRST Source
 *    (ADR-0007): it is materialized only when the book still has none, so
 *    attaching a physical Source to an Edition that already has Chapters
 *    never replaces them (ADR-0056 manual order survives);
 *  - Edition / Chapters / Source / Tracks ride one [writeBatchRunner]
 *    transaction, so a failed write leaves no partially added links of this
 *    materialization.
 *
 * The module does NOT clear a Tombstone or mark Personal Intent — those stay
 * at the explicit-import door — and it opens no browser.
 */
class EditionSourceWriter(
    private val dao: AudiobookDao,
    /**
     * The Room transaction seam (the same one [LibraryImport] and
     * [com.slukhayka.audiobooks.data.catalog.SourceCatalog] already carry):
     * every write of ONE materialization runs inside it. The composition root
     * supplies `database.withTransaction`; tests inject an in-memory
     * `withTransaction` — or a failing wrapper — to probe atomicity.
     */
    private val writeBatchRunner: suspend (suspend () -> Unit) -> Unit = { it() }
) {

    /** The rows one resolved page wrote (or found already stored). */
    data class Written(
        val edition: EditionEntity,
        val source: SourceEntity,
        /**
         * The Edition's logical chapters after the write: the page's list when
         * it was materialized, the stored list when the Edition already owned
         * one (never replaced here).
         */
        val chapters: List<ChapterEntity>,
        val tracks: List<SourceTrackEntity>
    )

    /**
     * Persists the adapter's resolved [detail] as the [sourceId] Source of
     * [bookId]'s Edition.
     *
     * @param existingEdition the stored Edition the Source attaches to, or
     *   null to insert [draftEdition]. The caller keeps its per-door insert
     *   defaults (id scheme, narrator placeholder, duration source); the
     *   module only owns the graph and its identity rules.
     * @param pageUrl the stored page URL when the adapter left [detail].url
     *   blank (the explicit import already passes the resolved URL).
     */
    suspend fun writeResolvedPage(
        bookId: String,
        bookTitle: String,
        sourceId: String,
        pageUrl: String,
        detail: SourceBookDetail,
        existingEdition: EditionEntity?,
        draftEdition: EditionEntity
    ): Written {
        val proposedEdition = existingEdition ?: draftEdition
        var written: Written? = null
        writeBatchRunner {
            val url = detail.url.ifBlank { pageUrl }
            val sourceKey = "$sourceId-${proposedEdition.id}"
            val matching = (dao.getSourcesForBookSync(bookId) + dao.getSourcesForEditionSync(proposedEdition.id))
                .distinctBy { it.id }
                .filter { it.type == sourceId && it.url == url && it.editionId == proposedEdition.id }
            require(matching.size <= 1) { "Ambiguous source page identity" }
            val existingSource = matching.singleOrNull() ?: dao.getSourceById(sourceKey)
            val edition = if (existingSource != null) {
                val storedEdition = dao.getEditionById(proposedEdition.id)
                require(existingSource.bookId == bookId && existingSource.editionId == proposedEdition.id &&
                    existingSource.type == sourceId && dao.getAudiobookById(bookId) != null &&
                    storedEdition?.workId == bookId) {
                    "Source page belongs to another Book or Edition"
                }
                checkNotNull(storedEdition)
            } else proposedEdition
            // Preserve discovery time, scan memory and an existing opaque ID.
            // The stable type/Edition source can still update its URL and policy.
            val source = existingSource?.copy(url = url, streamOnly = streamOnlyFor(sourceId))
                ?: SourceEntity(
                    id = sourceKey,
                    bookId = bookId,
                    editionId = edition.id,
                    type = sourceId,
                    url = url,
                    streamOnly = streamOnlyFor(sourceId),
                    addedAt = System.currentTimeMillis()
                )
            val materialized = MetadataAssertions.materializeChaptersAndTracks(
                editionId = edition.id,
                sourceId = source.id,
                bookId = bookId,
                bookTitle = bookTitle,
                chapters = detail.chapters
            )
            if (existingSource == null && existingEdition == null) dao.insertEdition(edition)
            // ADR-0007/0056: an Edition that already owns its logical chapters
            // keeps them exactly — this write only attaches the physical
            // Source and its tracks.
            val writtenChapters = if (dao.getChaptersListForBook(bookId).isEmpty()) {
                dao.insertChapters(materialized.chapters)
                materialized.chapters
            } else {
                dao.getChaptersListForBook(bookId)
            }
            dao.insertSources(listOf(source))
            dao.insertTracks(materialized.tracks)
            written = Written(edition, source, writtenChapters, materialized.tracks)
        }
        return checkNotNull(written)
    }
}
