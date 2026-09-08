package com.slukhayka.audiobooks.data.facets

import android.content.Context
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode
import com.slukhayka.audiobooks.data.metadata.SubmissionCursor
import com.slukhayka.audiobooks.data.metadata.SubmissionPageLimits
import com.slukhayka.audiobooks.data.metadata.SubmissionPublication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Last remote submission page fully committed to the local projection. */
interface SubmissionSyncCursorStore {
    fun load(): SubmissionCursor?
    fun save(cursor: SubmissionCursor)
}

class InMemorySubmissionSyncCursorStore(
    initial: SubmissionCursor? = null
) : SubmissionSyncCursorStore {
    private var cursor = initial

    override fun load(): SubmissionCursor? = cursor

    override fun save(cursor: SubmissionCursor) {
        this.cursor = cursor
    }
}

/** Durable high-water mark of the submission lane (own prefs, like the facet lane). */
class SharedPreferencesSubmissionSyncCursorStore(context: Context) : SubmissionSyncCursorStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): SubmissionCursor? {
        if (!prefs.contains(KEY_SUBMITTED_AT) || !prefs.contains(KEY_DOCUMENT_ID)) return null
        val submittedAt = prefs.getLong(KEY_SUBMITTED_AT, -1)
        val documentId = prefs.getString(KEY_DOCUMENT_ID, null).orEmpty()
        return SubmissionCursor(submittedAt, documentId)
            .takeIf { it.submittedAt >= 0 && it.documentId.isNotBlank() }
    }

    override fun save(cursor: SubmissionCursor) {
        require(cursor.submittedAt >= 0 && cursor.documentId.isNotBlank())
        check(
            prefs.edit()
                .putLong(KEY_SUBMITTED_AT, cursor.submittedAt)
                .putString(KEY_DOCUMENT_ID, cursor.documentId)
                .commit()
        ) { "Submission sync cursor was not persisted" }
    }

    companion object {
        internal const val PREFS_NAME = "submission_sync_cursor"
        private const val KEY_SUBMITTED_AT = "submitted_at"
        private const val KEY_DOCUMENT_ID = "document_id"
    }
}

/**
 * The frozen local-write seam of one published-submission page: the shared
 * delta lands in the local projection through this writer only. Room's
 * implementation is [RoomSubmissionProjectionWriter]; tests use a recording
 * fake (the [FacetDeltaSync]/[LocalFacetWriter] precedent).
 */
interface SubmissionProjectionWriter {
    suspend fun apply(publications: List<SubmissionPublication>)
}

/**
 * ADR-0035 / #605 — the consumption lane of listener submissions: reads one
 * bounded page of the shared submission collection and materializes it into
 * the LOCAL PROJECTION through the [SubmissionProjectionWriter] seam — the
 * same lane shape as [FacetDeltaSync] (mutex, bounded page, durable cursor,
 * degrade-never). A corrupt document is skipped by the codec before the
 * writer ever sees it (a miss, never a crash); a failing store read
 * contributes nothing.
 */
class SubmissionDeltaSync(
    private val sharedStore: SharedBookMetaStore,
    private val projectionWriter: SubmissionProjectionWriter,
    private val cursorStore: SubmissionSyncCursorStore
) {
    private val syncMutex = Mutex()

    data class ChainResult(
        val pagesApplied: Int,
        val publicationsApplied: Int
    )

    sealed interface PageResult {
        data class Applied(val publicationCount: Int) : PageResult
        data object NoChanges : PageResult
        data object Failed : PageResult
    }

    suspend fun syncPage(pageSize: Int = SubmissionPageLimits.MAX_PAGE_SIZE): PageResult =
        syncMutex.withLock { syncPageLocked(pageSize) }

    private suspend fun syncPageLocked(pageSize: Int): PageResult {
        return try {
            val page = sharedStore.getSubmissionPage(
                cursorStore.load(),
                SubmissionPageLimits.bounded(pageSize)
            )
            val nextCursor = page.nextCursor ?: return PageResult.NoChanges
            if (page.publications.isNotEmpty()) projectionWriter.apply(page.publications)
            withContext(Dispatchers.IO) { cursorStore.save(nextCursor) }
            PageResult.Applied(page.publications.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PageResult.Failed
        }
    }

    suspend fun syncAvailablePages(
        pageSize: Int = SubmissionPageLimits.MAX_PAGE_SIZE,
        maxPages: Int = MAX_PAGES_PER_SESSION
    ): ChainResult = syncMutex.withLock {
        var pagesApplied = 0
        var publicationsApplied = 0
        repeat(maxPages.coerceIn(0, MAX_PAGES_PER_SESSION)) {
            when (val result = syncPageLocked(pageSize)) {
                is PageResult.Applied -> {
                    pagesApplied++
                    publicationsApplied += result.publicationCount
                }

                PageResult.NoChanges,
                PageResult.Failed -> return ChainResult(pagesApplied, publicationsApplied)
            }
        }
        return ChainResult(pagesApplied, publicationsApplied)
    }

    private companion object {
        const val MAX_PAGES_PER_SESSION = 20
    }
}

/**
 * ADR-0035 / #605 — the local projection of one published submission,
 * mirroring the #604 import door's materialization EXACTLY (same id
 * formulas, same found-or-create by mergeKey) but in the CATALOG shape: a
 * Works row plus a WorkSource row, an Edition, chapters, the Source and its
 * watch-URL tracks — so the merged catalog, «Новинки» and the existing
 * resolver-seam playback see the published source. NEVER an Audiobooks row
 * or a library entry: the submitter's local copy is owned by the import
 * door (ADR-0035 «копія подавача — звичайний локальний Source») and a
 * shared delta must not silently add library books to another install.
 *
 * Honest by construction:
 *  - the same normalized URL already present is a no-op (store-level dedup
 *    is mirrored here — the second arrival never duplicates rows);
 *  - the same narration (mergeKey) finds-or-creates ONE Work — a duplicate
 *    publication lands as a SECOND Source of the SAME Edition, never a
 *    second Work;
 *  - an unknown access mode or a locally tombstoned Work (ADR-0005) is
 *    skipped — a shared delta never resurrects a deleted book;
 *  - a metadata-only publication (no chapters) materializes the Work +
 *    Edition facets but NO Source/tracks — playback honestly finds nothing
 *    (ADR-0019), nothing is fabricated.
 */
class RoomSubmissionProjectionWriter(private val dao: AudiobookDao) : SubmissionProjectionWriter {

    private val facetWriter: LocalFacetWriter = RoomLocalFacetWriter(dao)

    override suspend fun apply(publications: List<SubmissionPublication>) {
        publications.forEach { materialize(it) }
    }

    private suspend fun materialize(publication: SubmissionPublication) {
        val normalizedUrl = publication.sourceUrl.trim()
        // The same link already present — a no-op (ADR-0007).
        if (dao.getSourceByUrl(normalizedUrl) != null) return
        // Only known playable modes materialize; unknown modes are skipped
        // honestly (an old app must not guess at a future mode).
        if (publication.accessMode != SubmissionAccessMode.YOUTUBE) return

        // mergeKey dedup — the same narration lands on ONE Work.
        val mergeKey = MergeKey.keyFor(publication.title, publication.author.orEmpty())
        val existingWork = dao.findWorkByMergeKey(mergeKey)
        val workId = existingWork?.id ?: if (mergeKey.isNotBlank()) {
            mergeKey
        } else {
            // Blank identity — its own stable Work (the writeWorkEdition
            // precedent); it never merges, by definition.
            "w-youtube-${Integer.toHexString(normalizedUrl.hashCode())}"
        }
        // ADR-0005: a shared delta never resurrects a locally tombstoned
        // Work — only the explicit add clears the tombstone. Checked BEFORE
        // anything is written, so a tombstoned Work gains no rows at all.
        if (dao.isBookTombstoned(workId)) return
        if (existingWork == null) {
            dao.upsertWork(
                WorkEntity(
                    id = workId,
                    mergeKey = mergeKey,
                    title = MetadataAssertions.normalizeTitle(publication.title),
                    author = publication.author?.trim().orEmpty(),
                    addedAt = publication.submittedAt
                )
            )
        }

        val narrator = publication.narrator?.takeIf { it.isNotBlank() } ?: SUBMISSION_NARRATOR
        val editionId = EditionId.forBook(mergeKey, workId, narrator)
        val existingEdition = dao.getEditionById(editionId)
        if (existingEdition == null) {
            dao.insertEdition(
                EditionEntity(
                    id = editionId,
                    workId = workId,
                    narrator = narrator,
                    totalChapters = publication.chapters.size,
                    totalDurationSeconds = publication.durationSeconds ?: 0L,
                    addedAt = publication.submittedAt
                )
            )
        }

        // Chapters extend to the observed list; never shrink an Edition.
        val existingChapters = existingEdition?.totalChapters ?: 0
        if (publication.chapters.size > existingChapters) {
            dao.insertChapters(
                publication.chapters.drop(existingChapters).mapIndexed { offset, chapter ->
                    ChapterEntity(
                        id = "$workId-ch${existingChapters + offset + 1}",
                        bookId = workId,
                        editionId = editionId,
                        chapterIndex = existingChapters + offset,
                        title = chapter.title,
                        durationSeconds = 0L
                    )
                }
            )
            dao.replaceEdition(
                (existingEdition ?: EditionEntity(
                    id = editionId, workId = workId, narrator = narrator
                )).copy(totalChapters = publication.chapters.size)
            )
        }

        // The merged catalog's source surface (feed sourceCount, book page) —
        // landed even for a metadata-only publication, where it IS the honest
        // «Джерело недоступне» claim: the source exists, playback does not.
        dao.safeUpsertWorkSource(
            WorkSourceEntity(
                id = "$workId|youtube|${Integer.toHexString(normalizedUrl.hashCode())}",
                workId = workId,
                sourceId = "youtube",
                sourceUrl = normalizedUrl,
                streamOnly = false,
                coverImageUrl = publication.coverUrl,
                durationSeconds = publication.durationSeconds,
                addedAt = publication.submittedAt
            )
        )
        // The Edition's facet projection — the feed's narrator/language/
        // chapter-count dimensions see the rendition (the R1 #508 seam).
        facetWriter.applyEditionFacet(
            editionId = editionId,
            domainWorkId = workId,
            narrator = narrator,
            chapterCount = publication.chapters.size,
            updatedAt = publication.submittedAt
        )

        // A metadata-only publication materializes identity only — NO Source
        // rows, NO tracks: playback honestly finds nothing (ADR-0019), never
        // a fabricated stream.
        if (publication.chapters.isEmpty()) return

        val sourceId = "youtube-$editionId-${Integer.toHexString(normalizedUrl.hashCode())}"
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = sourceId,
                    bookId = workId,
                    editionId = editionId,
                    type = "youtube",
                    url = normalizedUrl,
                    addedAt = publication.submittedAt
                )
            )
        )
        dao.insertTracks(
            publication.chapters.mapIndexed { index, chapter ->
                SourceTrackEntity(
                    id = MetadataAssertions.trackId(sourceId, index),
                    sourceId = sourceId,
                    trackIndex = index,
                    url = chapter.watchUrl,
                    localFilePath = null,
                    contentHash = null,
                    isDownloaded = false
                )
            }
        )
    }

    private companion object {
        const val SUBMISSION_NARRATOR = "YouTube"
    }
}