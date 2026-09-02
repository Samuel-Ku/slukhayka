package com.slukhayka.audiobooks.data.db

import androidx.room.Entity

/**
 * Spec #462 Implementation Decision 6 (#467) — one persisted feed snapshot:
 * the cards a source's feed (новинки / каталог / the 4read homepage sections)
 * served at [fetchedAt], stored as JSON so the Огляд feed reads the database
 * first and the network is hit ONLY after the feed's TTL (FeedSnapshotPolicy:
 * новинки 6 hours, каталог 24 hours) or on an explicit user refresh.
 *
 * One row per (source, feed, page/cursor): [pageCursor] keeps the cursor the
 * snapshot was taken at — '' for a whole-feed snapshot, a page number for a
 * feed pulled through the FeedCursor seam (#466), so snapshot storage
 * COMPOSES with the cursor instead of replacing it. Nothing here is a book
 * row: the cards are decoded on read and flow through the existing
 * catalogue write path (LibraryImport.upsertCatalogBook), where tombstones
 * keep blocking reimport — deleting books stays forbidden.
 */
@Entity(
    tableName = "feed_snapshots",
    primaryKeys = ["sourceId", "feedKey", "pageCursor"]
)
data class FeedSnapshotEntity(
    /** The source the feed belongs to (SourceIds value, e.g. "sluhayua"). */
    val sourceId: String,
    /** Which feed of the source — FeedSnapshotPolicy feed keys. */
    val feedKey: String,
    /** The page/cursor the snapshot covers ('' = the whole feed). */
    val pageCursor: String = "",
    /** When the snapshot was fetched (epoch millis). */
    val fetchedAt: Long,
    /** The cards, JSON-encoded (FeedSnapshotCodec). */
    val cardsJson: String
)
