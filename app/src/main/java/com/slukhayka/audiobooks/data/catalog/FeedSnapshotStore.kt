package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spec #462 Implementation Decision 6 (#467) — the Room-backed feed-snapshot
 * store. The one read/write door over `feed_snapshots` for the Огляд feeds:
 * [freshBooks] / [freshHomepage] return the persisted cards ONLY while the
 * snapshot is inside its TTL (the pure [FeedSnapshotPolicy] decides —
 * новинки 6 h, каталог 24 h), and [saveBooks] / [saveHomepage] persist what a
 * live fetch just served, so the NEXT read skips the network.
 *
 * Composes with, never replaces, the live seams: the network half still runs
 * through the source adapters (and a paged pull still walks [com.slukhayka.audiobooks.data.source.FeedCursor]) —
 * the store only remembers the page/cursor a snapshot was taken at
 * ([FeedSnapshotEntity.pageCursor]). Books decoded here are EPHEMERAL feed
 * cards; they land in Room only through the existing catalogue write path
 * ([LibraryImport.upsertCatalogBook]), where tombstones keep blocking
 * reimport — deleting books stays forbidden.
 *
 * The clock is injectable for the JVM tests (a fake clock pins the TTL
 * decision without sleeping).
 */
class FeedSnapshotStore(
    private val dao: AudiobookDao,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    /**
     * The fresh snapshot cards of one feed, or null when the feed has no
     * snapshot or it is stale/forced-stale (the caller then hits the network).
     * Multi-page feeds read back in page order as one list.
     */
    suspend fun freshBooks(
        sourceId: String,
        feedKey: String,
        forceRefresh: Boolean = false
    ): List<SourceBook>? = withContext(Dispatchers.IO) {
        val rows = dao.getFeedSnapshots(sourceId, feedKey)
        val fetchedAt = rows.minOfOrNull { it.fetchedAt }
        if (FeedSnapshotPolicy.needsNetwork(feedKey, fetchedAt, nowMillis(), forceRefresh)) {
            return@withContext null
        }
        val cards = rows.flatMap { row -> FeedSnapshotCodec.decodeBooks(row.cardsJson) }
        // An empty decode (a corrupt snapshot, or an empty feed a source once
        // served) is a cache MISS, never an answer — the feed must not freeze
        // as empty for a whole TTL.
        cards.ifEmpty { null }
    }

    /** Remembers what a live fetch just served, under the feed's page/cursor. */
    suspend fun saveBooks(
        sourceId: String,
        feedKey: String,
        books: List<SourceBook>,
        pageCursor: String = ""
    ): Unit = withContext(Dispatchers.IO) {
        // A new full-feed snapshot supersedes the previous page shape: one
        // feed, one current snapshot — the old rows are cleared first so a
        // re-paged feed never reads stale pages beside fresh ones.
        dao.clearFeedSnapshots(sourceId, feedKey)
        dao.upsertFeedSnapshot(
            FeedSnapshotEntity(
                sourceId = sourceId,
                feedKey = feedKey,
                pageCursor = pageCursor,
                fetchedAt = nowMillis(),
                cardsJson = FeedSnapshotCodec.encodeBooks(books)
            )
        )
    }

    /** The fresh 4read homepage snapshot (sections + genre nav), or null. */
    suspend fun freshHomepage(forceRefresh: Boolean = false): FeedSnapshotCodec.HomepageSnapshot? =
        withContext(Dispatchers.IO) {
            val row = dao.getFeedSnapshot(HOMEPAGE_SOURCE_ID, FeedSnapshotPolicy.FEED_HOMEPAGE_SECTIONS)
                ?: return@withContext null
            if (FeedSnapshotPolicy.needsNetwork(
                    FeedSnapshotPolicy.FEED_HOMEPAGE_SECTIONS,
                    row.fetchedAt,
                    nowMillis(),
                    forceRefresh
                )
            ) {
                return@withContext null
            }
            FeedSnapshotCodec.decodeHomepage(row.cardsJson)
        }

    /** Remembers the homepage the live fetch just parsed. */
    suspend fun saveHomepage(sections: List<CatalogSection>, genres: List<CatalogGenre>): Unit =
        withContext(Dispatchers.IO) {
            dao.clearFeedSnapshots(HOMEPAGE_SOURCE_ID, FeedSnapshotPolicy.FEED_HOMEPAGE_SECTIONS)
            dao.upsertFeedSnapshot(
                FeedSnapshotEntity(
                    sourceId = HOMEPAGE_SOURCE_ID,
                    feedKey = FeedSnapshotPolicy.FEED_HOMEPAGE_SECTIONS,
                    pageCursor = "",
                    fetchedAt = nowMillis(),
                    cardsJson = FeedSnapshotCodec.encodeHomepage(
                        FeedSnapshotCodec.HomepageSnapshot(sections, genres)
                    )
                )
            )
        }

    companion object {
        /** The 4read homepage snapshot anchors to the 4read source id. */
        const val HOMEPAGE_SOURCE_ID: String = SourceIds.FOUR_READ
    }
}
