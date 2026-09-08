package com.slukhayka.audiobooks.data.facets

import android.content.Context
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.metadata.SharedTombstone
import com.slukhayka.audiobooks.data.metadata.SharedTombstoneCursor
import com.slukhayka.audiobooks.data.metadata.SharedTombstonePageLimits
import com.slukhayka.audiobooks.data.metadata.TombstoneTargetKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Last remote tombstone page fully committed to the local tombstone machinery. */
interface SharedTombstoneSyncCursorStore {
    fun load(): SharedTombstoneCursor?
    fun save(cursor: SharedTombstoneCursor)
}

class InMemorySharedTombstoneSyncCursorStore(
    initial: SharedTombstoneCursor? = null
) : SharedTombstoneSyncCursorStore {
    private var cursor = initial

    override fun load(): SharedTombstoneCursor? = cursor

    override fun save(cursor: SharedTombstoneCursor) {
        this.cursor = cursor
    }
}

/** Durable high-water mark of the shared-tombstone lane (own prefs). */
class SharedPreferencesSharedTombstoneSyncCursorStore(context: Context) : SharedTombstoneSyncCursorStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): SharedTombstoneCursor? {
        if (!prefs.contains(KEY_PLACED_AT) || !prefs.contains(KEY_DOCUMENT_ID)) return null
        val placedAt = prefs.getLong(KEY_PLACED_AT, -1)
        val documentId = prefs.getString(KEY_DOCUMENT_ID, null).orEmpty()
        return SharedTombstoneCursor(placedAt, documentId)
            .takeIf { it.placedAt >= 0 && it.documentId.isNotBlank() }
    }

    override fun save(cursor: SharedTombstoneCursor) {
        require(cursor.placedAt >= 0 && cursor.documentId.isNotBlank())
        check(
            prefs.edit()
                .putLong(KEY_PLACED_AT, cursor.placedAt)
                .putString(KEY_DOCUMENT_ID, cursor.documentId)
                .commit()
        ) { "Shared-tombstone sync cursor was not persisted" }
    }

    companion object {
        internal const val PREFS_NAME = "shared_tombstone_sync_cursor"
        private const val KEY_PLACED_AT = "placed_at"
        private const val KEY_DOCUMENT_ID = "document_id"
    }
}

/**
 * The frozen local-write seam of one shared-tombstone page: the curator's
 * block lands in the LOCAL tombstone machinery through this writer only
 * ([FacetDeltaSync]/[LocalFacetWriter] precedent).
 */
interface TombstoneProjectionWriter {
    suspend fun apply(tombstones: List<SharedTombstone>)
}

/**
 * ADR-0035 / #607 — the consumption lane of curator Shared Tombstones: reads
 * one bounded page of the shared tombstone collection and enforces it
 * locally through the [TombstoneProjectionWriter] seam — the same lane shape
 * as [SubmissionDeltaSync] (mutex, bounded page, durable cursor,
 * degrade-never). A corrupt document is skipped by the codec before the
 * writer ever sees it; a failing store read contributes nothing.
 */
class SharedTombstoneDeltaSync(
    private val sharedStore: SharedBookMetaStore,
    private val projectionWriter: TombstoneProjectionWriter,
    private val cursorStore: SharedTombstoneSyncCursorStore
) {
    private val syncMutex = Mutex()

    data class ChainResult(
        val pagesApplied: Int,
        val tombstonesApplied: Int
    )

    sealed interface PageResult {
        data class Applied(val tombstoneCount: Int) : PageResult
        data object NoChanges : PageResult
        data object Failed : PageResult
    }

    suspend fun syncPage(pageSize: Int = SharedTombstonePageLimits.MAX_PAGE_SIZE): PageResult =
        syncMutex.withLock { syncPageLocked(pageSize) }

    private suspend fun syncPageLocked(pageSize: Int): PageResult {
        return try {
            val page = sharedStore.getSharedTombstonePage(
                cursorStore.load(),
                SharedTombstonePageLimits.bounded(pageSize)
            )
            val nextCursor = page.nextCursor ?: return PageResult.NoChanges
            if (page.tombstones.isNotEmpty()) projectionWriter.apply(page.tombstones)
            withContext(Dispatchers.IO) { cursorStore.save(nextCursor) }
            PageResult.Applied(page.tombstones.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PageResult.Failed
        }
    }

    suspend fun syncAvailablePages(
        pageSize: Int = SharedTombstonePageLimits.MAX_PAGE_SIZE,
        maxPages: Int = MAX_PAGES_PER_SESSION
    ): ChainResult = syncMutex.withLock {
        var pagesApplied = 0
        var tombstonesApplied = 0
        repeat(maxPages.coerceIn(0, MAX_PAGES_PER_SESSION)) {
            when (val result = syncPageLocked(pageSize)) {
                is PageResult.Applied -> {
                    pagesApplied++
                    tombstonesApplied += result.tombstoneCount
                }

                PageResult.NoChanges,
                PageResult.Failed -> return ChainResult(pagesApplied, tombstonesApplied)
            }
        }
        return ChainResult(pagesApplied, tombstonesApplied)
    }

    private companion object {
        const val MAX_PAGES_PER_SESSION = 20
    }
}

/**
 * ADR-0035 / #607 — the LOCAL enforcement of one curator Shared Tombstone,
 * the ADR-0005 model continued onto the collective channel:
 *
 *  - the LOCAL tombstone marker (bookId = the work's local id — the same
 *    mergeKey-derived id every install derives) blocks FUTURE materialization:
 *    the #605 projection writer and every guarded catalog write refuse the
 *    tombstoned work, so a fresh install can never resurrect it;
 *  - the source's catalog CLAIM rows are removed ONLY where no local copy
 *    owns them (no audiobooks row for the work). On an install with a real
 *    copy (the submitter's, or any downloader's) the tombstone lands as a
 *    marker alone — the downloaded files, the Library row and the Listening
 *    State survive untouched and stay playable.
 *
 * Target scopes (the curator chooses): a WORK tombstone (by mergeKey)
 * removes the whole work's claim surface; a SOURCE tombstone (by normalized
 * URL) removes exactly that source's rows — chapters stay (they are shared
 * by the work's other sources). The work-level marker is used for both, so
 * the block is sticky across installs; the curator's SOURCE scope therefore
 * also blocks the work's future submission channel (conservative: a
 * tombstoned submission is usually the whole work's junk).
 */
class RoomTombstoneProjectionWriter(private val dao: AudiobookDao) : TombstoneProjectionWriter {

    override suspend fun apply(tombstones: List<SharedTombstone>) {
        tombstones.forEach { applyOne(it) }
    }

    private suspend fun applyOne(tombstone: SharedTombstone) {
        when (tombstone.targetKind) {
            TombstoneTargetKind.WORK -> tombstoneWork(tombstone.mergeKey ?: return)
            TombstoneTargetKind.SOURCE -> tombstoneSource(tombstone.sourceUrl ?: return)
        }
    }

    private suspend fun tombstoneWork(mergeKey: String) {
        val workId = dao.findWorkByMergeKey(mergeKey)?.id ?: return
        dao.insertTombstone(TombstoneEntity(bookId = workId))
        if (!hasLibraryCopy(workId)) removeClaimRows(workId)
    }

    private suspend fun tombstoneSource(sourceUrl: String) {
        val source = dao.getSourceByUrl(sourceUrl.trim()) ?: return
        // Catalog shape: the source's bookId IS the work id; the submitter's
        // library shape: the audiobooks row's workId anchors the work.
        val workId = dao.getAudiobookById(source.bookId)
            ?.workId
            ?.takeIf { it.isNotBlank() }
            ?: source.bookId
        dao.insertTombstone(TombstoneEntity(bookId = workId))
        // A real local copy owns this source — keep its rows (copy + progress
        // + files survive and stay playable). Otherwise remove the claim.
        if (dao.getAudiobookById(source.bookId) == null) {
            dao.deleteTracksForSource(source.id)
            dao.deleteSourceById(source.id)
            dao.deleteWorkSourceForUrl(workId, source.url)
        }
    }

    private suspend fun hasLibraryCopy(workId: String): Boolean =
        dao.getAllAudiobooksOnce().any { it.workId == workId }

    private suspend fun removeClaimRows(workId: String) {
        // Tracks first — the track delete joins the sources table.
        dao.deleteTracksForBook(workId)
        dao.deleteSourcesForBook(workId)
        dao.deleteWorkSourcesForWork(workId)
        dao.deleteChaptersForBook(workId)
    }
}