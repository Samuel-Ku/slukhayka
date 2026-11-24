package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * #523/#1238 — the persisted block store over the existing `feed_snapshots`
 * rows: one deterministic row per identity, so activating a new snapshot is a
 * single atomic REPLACE and the last good block survives every failed attempt.
 * The identity includes the canonical provenance context (#1238), so related
 * collections of one Source get separate rows. A context-bearing key also
 * falls back to the legacy source-scoped row, so an upgrade still reads the
 * last good block instead of starting cold. Best-effort by contract — a miss
 * or a broken document is a cache miss, never a crash.
 */
class RoomCollectiveFeedBlockStore(
    private val dao: AudiobookDao
) : CollectiveFeedBlockStore {

    override suspend fun active(blockKey: String): CollectiveFeedBlock? =
        withContext(Dispatchers.IO) {
            val ref = parseCollectiveBlockKey(blockKey) ?: return@withContext null
            val row = findRow(ref) ?: return@withContext null
            CollectiveFeedBlockCodec.decode(row.cardsJson)
        }

    override fun observeChanges(blockKeys: List<String>): Flow<Unit> {
        val snapshotKeys = blockKeys.map { key ->
            val ref = requireNotNull(parseCollectiveBlockKey(key)) { "Invalid collective block key: $key" }
            "${ref.sourceId}|${collectiveFeedKey(ref)}"
        }
        // SELECT * observes payload bytes, not just the unchanged block identity.
        return dao.observeCollectiveFeedSnapshots(snapshotKeys).map { Unit }
    }

    override suspend fun keys(sourceId: String, kind: CollectiveBlockKind): List<String> =
        withContext(Dispatchers.IO) {
            dao.getCollectiveSourceSnapshots(sourceId).mapNotNull { row ->
                val block = CollectiveFeedBlockCodec.decode(row.cardsJson) ?: return@mapNotNull null
                val ref = parseCollectiveBlockKey(block.blockKey) ?: return@mapNotNull null
                block.blockKey.takeIf {
                    ref.sourceId == sourceId && ref.kind == kind && block.sourceId == sourceId &&
                        block.kind == kind && row.feedKey == collectiveFeedKey(ref)
                }
            }.distinct().sorted()
        }

    override fun observeSourceChanges(sourceIds: List<String>): Flow<Unit> =
        dao.observeCollectiveSourceSnapshots(sourceIds).map { Unit }

    override suspend fun activate(block: CollectiveFeedBlock): Boolean =
        withContext(Dispatchers.IO) {
            // An empty snapshot never becomes active; the deterministic key
            // makes the write one atomic REPLACE.
            if (block.cards.isEmpty()) return@withContext false
            val ref = parseCollectiveBlockKey(block.blockKey) ?: return@withContext false
            dao.upsertFeedSnapshot(
                FeedSnapshotEntity(
                    sourceId = block.sourceId,
                    feedKey = collectiveFeedKey(ref),
                    pageCursor = "",
                    fetchedAt = block.fetchedAt,
                    cardsJson = CollectiveFeedBlockCodec.encode(block)
                )
            )
            true
        }

    override suspend fun activateIfUnchanged(
        expected: CollectiveFeedBlock?, block: CollectiveFeedBlock
    ): Boolean = withContext(Dispatchers.IO) {
        if (block.cards.isEmpty()) return@withContext false
        val ref = parseCollectiveBlockKey(block.blockKey) ?: return@withContext false
        dao.activateCollectiveFeedIfUnchanged(
            expected,
            FeedSnapshotEntity(
                sourceId = block.sourceId,
                feedKey = collectiveFeedKey(ref),
                pageCursor = "",
                fetchedAt = block.fetchedAt,
                cardsJson = CollectiveFeedBlockCodec.encode(block)
            ),
            fallbackFeedKey = collectiveFeedKey(ref.kind).takeIf { ref.provenanceContext.isNotEmpty() }
        )
    }

    override suspend fun activateIfNewer(block: CollectiveFeedBlock): Boolean =
        withContext(Dispatchers.IO) {
            if (block.cards.isEmpty()) return@withContext false
            val ref = parseCollectiveBlockKey(block.blockKey) ?: return@withContext false
            dao.activateCollectiveFeedIfNewer(
                FeedSnapshotEntity(
                    sourceId = block.sourceId,
                    feedKey = collectiveFeedKey(ref),
                    pageCursor = "",
                    fetchedAt = block.fetchedAt,
                    cardsJson = CollectiveFeedBlockCodec.encode(block)
                ),
                fallbackFeedKey = collectiveFeedKey(ref.kind).takeIf { ref.provenanceContext.isNotEmpty() }
            )
        }

    override suspend fun recordAttempt(blockKey: String, attempt: CollectiveAttempt) =
        withContext(Dispatchers.IO) {
            val ref = parseCollectiveBlockKey(blockKey) ?: return@withContext
            val feedKey = collectiveFeedKey(ref)
            dao.recordCollectiveFeedAttempt(
                ref.sourceId, feedKey, attempt,
                fallbackFeedKey = collectiveFeedKey(ref.kind).takeIf { ref.provenanceContext.isNotEmpty() }
            )
        }

    override suspend fun recordRevision(blockKey: String, revision: Long, expectedVersion: Long) =
        withContext(Dispatchers.IO) {
            val ref = parseCollectiveBlockKey(blockKey) ?: return@withContext
            dao.recordCollectiveFeedRevision(ref.sourceId, collectiveFeedKey(ref), revision, expectedVersion)
        }

    /**
     * The exact identity's row, or the legacy source-scoped row of the same
     * kind. The fallback is read-only: a new activation always writes the
     * exact key, so siblings never share a row again.
     */
    private suspend fun findRow(ref: CollectiveBlockRef): FeedSnapshotEntity? {
        dao.getFeedSnapshot(ref.sourceId, collectiveFeedKey(ref))?.let { return it }
        if (ref.provenanceContext.isEmpty()) return null
        return dao.getFeedSnapshot(ref.sourceId, collectiveFeedKey(ref.kind))
    }
}
