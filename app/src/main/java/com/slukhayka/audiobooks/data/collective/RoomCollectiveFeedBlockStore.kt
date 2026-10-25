package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import kotlinx.coroutines.Dispatchers
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

    override suspend fun recordAttempt(blockKey: String, attempt: CollectiveAttempt) =
        withContext(Dispatchers.IO) {
            val ref = parseCollectiveBlockKey(blockKey) ?: return@withContext
            val row = findRow(ref) ?: return@withContext
            val block = CollectiveFeedBlockCodec.decode(row.cardsJson) ?: return@withContext
            // The cards, identity, version and time window stay exactly as the
            // last good snapshot had them.
            dao.upsertFeedSnapshot(row.copy(cardsJson = CollectiveFeedBlockCodec.encode(block.copy(lastAttempt = attempt))))
        }

    override suspend fun recordRevision(blockKey: String, revision: Long, expectedVersion: Long) =
        withContext(Dispatchers.IO) {
            val ref = parseCollectiveBlockKey(blockKey) ?: return@withContext
            val row = findRow(ref) ?: return@withContext
            val block = CollectiveFeedBlockCodec.decode(row.cardsJson) ?: return@withContext
            // A late publish answer only labels the observation it belongs to.
            if (block.version != expectedVersion) return@withContext
            dao.upsertFeedSnapshot(
                row.copy(cardsJson = CollectiveFeedBlockCodec.encode(block.copy(revision = revision)))
            )
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
