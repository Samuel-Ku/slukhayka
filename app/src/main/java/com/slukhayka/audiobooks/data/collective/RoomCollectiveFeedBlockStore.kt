package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import kotlinx.coroutines.Dispatchers
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

    override suspend fun recordAttempt(blockKey: String, attempt: CollectiveAttempt) =
        withContext(Dispatchers.IO) {
            val ref = parseCollectiveBlockKey(blockKey) ?: return@withContext
            val feedKey = collectiveFeedKey(ref.kind)
            val row = dao.getFeedSnapshot(ref.sourceId, feedKey) ?: return@withContext
            val block = CollectiveFeedBlockCodec.decode(row.cardsJson) ?: return@withContext
            // The cards, identity, version and time window stay exactly as the
            // last good snapshot had them.
            dao.upsertFeedSnapshot(row.copy(cardsJson = CollectiveFeedBlockCodec.encode(block.copy(lastAttempt = attempt))))
        }
}
