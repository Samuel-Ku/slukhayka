package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * #523 — the persisted block store over the existing `feed_snapshots` rows:
 * one deterministic row per (sourceId, `collective-<kind>`), so activating a
 * new snapshot is a single atomic REPLACE and the last good block survives
 * every failed attempt. Best-effort by contract — a miss or a broken document
 * is a cache miss, never a crash.
 */
class RoomCollectiveFeedBlockStore(
    private val dao: AudiobookDao
) : CollectiveFeedBlockStore {

    override suspend fun active(blockKey: String): CollectiveFeedBlock? =
        withContext(Dispatchers.IO) {
            val ref = parseCollectiveBlockKey(blockKey) ?: return@withContext null
            val row = dao.getFeedSnapshot(ref.sourceId, collectiveFeedKey(ref.kind))
                ?: return@withContext null
            CollectiveFeedBlockCodec.decode(row.cardsJson)
        }

    override fun observeChanges(blockKeys: List<String>): Flow<Unit> {
        val snapshotKeys = blockKeys.map { key ->
            val ref = requireNotNull(parseCollectiveBlockKey(key)) { "Invalid collective block key: $key" }
            "${ref.sourceId}|${collectiveFeedKey(ref.kind)}"
        }
        // SELECT * observes payload bytes, not just the unchanged block identity.
        return dao.observeCollectiveFeedSnapshots(snapshotKeys).map { Unit }
    }

    override suspend fun activate(block: CollectiveFeedBlock): Boolean =
        withContext(Dispatchers.IO) {
            // An empty snapshot never becomes active; the deterministic key
            // makes the write one atomic REPLACE.
            if (block.cards.isEmpty()) return@withContext false
            dao.upsertFeedSnapshot(
                FeedSnapshotEntity(
                    sourceId = block.sourceId,
                    feedKey = collectiveFeedKey(block.kind),
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
        dao.activateCollectiveFeedIfUnchanged(
            expected,
            FeedSnapshotEntity(
                sourceId = block.sourceId,
                feedKey = collectiveFeedKey(block.kind),
                pageCursor = "",
                fetchedAt = block.fetchedAt,
                cardsJson = CollectiveFeedBlockCodec.encode(block)
            )
        )
    }

    override suspend fun activateIfNewer(block: CollectiveFeedBlock): Boolean =
        withContext(Dispatchers.IO) {
            if (block.cards.isEmpty()) return@withContext false
            dao.activateCollectiveFeedIfNewer(
                FeedSnapshotEntity(
                    sourceId = block.sourceId,
                    feedKey = collectiveFeedKey(block.kind),
                    pageCursor = "",
                    fetchedAt = block.fetchedAt,
                    cardsJson = CollectiveFeedBlockCodec.encode(block)
                )
            )
        }

    override suspend fun recordAttempt(blockKey: String, attempt: CollectiveAttempt) =
        withContext(Dispatchers.IO) {
            val ref = parseCollectiveBlockKey(blockKey) ?: return@withContext
            val feedKey = collectiveFeedKey(ref.kind)
            dao.recordCollectiveFeedAttempt(ref.sourceId, feedKey, attempt)
        }
}
