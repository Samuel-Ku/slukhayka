package com.slukhayka.audiobooks.data.collective

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #527 / ADR-0028 — the transport seam of the SHARED collective-block lane.
 * It speaks the pure [CollectiveFeedBlock] contract, so the delta logic is
 * unit-testable without Firebase and the Firestore adapter stays thin. Every
 * method is best-effort: a miss, a failure or a corrupt document contributes
 * nothing and never throws.
 */
interface CollectiveBlockStore {

    /** Publishes one observed block; a rejected/invalid block is a no-op. */
    suspend fun putBlock(block: CollectiveFeedBlock) = Unit

    /** Bounded ordered remote delta page of blocks, ordered by the server revision. */
    suspend fun getBlocksPage(after: CollectiveBlockCursor?, limit: Int): CollectiveBlockPage =
        CollectiveBlockPage(emptyList(), null)

    /** #1238 — one shared row by identity, for a follower awaiting the owner's result. */
    suspend fun getBlock(blockKey: String): CollectiveFeedBlock? = null
}

/** The last shared block page fully mirrored locally. */
interface CollectiveBlockSyncCursorStore {
    fun load(): CollectiveBlockCursor?
    fun save(cursor: CollectiveBlockCursor)
}

class InMemoryCollectiveBlockSyncCursorStore(
    initial: CollectiveBlockCursor? = null
) : CollectiveBlockSyncCursorStore {
    private var cursor = initial

    override fun load(): CollectiveBlockCursor? = cursor

    override fun save(cursor: CollectiveBlockCursor) {
        this.cursor = cursor
    }
}

/**
 * Durable high-water mark of the shared block lane (own prefs).
 *
 * #1238 — the mark is the SERVER revision of the last applied page, not the
 * observation time. A cursor written by the pre-#1238 code carries only
 * `fetched_at`; it is treated as ABSENT on purpose: re-reading the bounded few
 * pages is cheap and safe (activation is idempotent by revision), while
 * guessing a revision from a device clock would silently skip rows.
 */
class SharedPreferencesCollectiveBlockSyncCursorStore(context: Context) : CollectiveBlockSyncCursorStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): CollectiveBlockCursor? {
        if (!prefs.contains(KEY_REVISION) || !prefs.contains(KEY_DOCUMENT_ID)) return null
        val revision = prefs.getLong(KEY_REVISION, -1L)
        val documentId = prefs.getString(KEY_DOCUMENT_ID, null).orEmpty()
        if (revision < 0L || documentId.isBlank()) return null
        return CollectiveBlockCursor(revision, documentId)
    }

    override fun save(cursor: CollectiveBlockCursor) {
        require(cursor.revision >= 0L && cursor.documentId.isNotBlank())
        check(
            prefs.edit()
                .putLong(KEY_REVISION, cursor.revision)
                .putString(KEY_DOCUMENT_ID, cursor.documentId)
                .remove(KEY_LEGACY_FETCHED_AT)
                .commit()
        ) { "Collective block sync cursor was not persisted" }
    }

    companion object {
        internal const val PREFS_NAME = "collective_block_sync_cursor"
        private const val KEY_REVISION = "revision"
        private const val KEY_DOCUMENT_ID = "document_id"
        private const val KEY_LEGACY_FETCHED_AT = "fetched_at"
    }
}

/**
 * #527/#1238 — mirrors the blocks other installs observed into this install's
 * local snapshot store, so «Огляд» shows a genre/new-arrivals block WITHOUT
 * repeating the genre or catalogue request.
 *
 * Ordering is the SERVER revision of the shared row ([CollectiveBlockCursor]).
 * [CollectiveFeedBlock.fetchedAt] stays the observation time and is only the
 * tie-breaker for the pre-#1238 rows that all carry revision 0. A shared row
 * that is not newer never replaces a newer local activation; an empty
 * candidate never does. Best-effort by contract: a failing page read leaves
 * the cursor and the local blocks exactly as they were, and a single bad card
 * never aborts the page.
 */
class CollectiveBlockSync(
    private val store: CollectiveBlockStore?,
    private val local: CollectiveFeedBlockStore,
    private val cursorStore: CollectiveBlockSyncCursorStore,
    private val maxPages: Int = DEFAULT_MAX_PAGES
) {

    /** @return how many remote blocks this pass actually mirrored. */
    suspend fun syncOnce(pageSize: Int = DEFAULT_PAGE_SIZE): Int = withContext(Dispatchers.IO) {
        val store = store ?: return@withContext 0
        val bounded = CollectivePageLimits.bounded(pageSize)
        if (bounded <= 0) return@withContext 0
        var cursor = cursorStore.load()
        var applied = 0
        var pages = 0
        while (pages < maxPages.coerceAtLeast(1)) {
            val page = runCatching { store.getBlocksPage(cursor, bounded) }
                .getOrElse { return@withContext applied }
            if (page.blocks.isEmpty() && page.nextCursor == null) return@withContext applied
            if (cursor != null && page.nextCursor == cursor) return@withContext applied
            for (block in page.blocks) {
                val mirrored = runCatching {
                    val existing = local.active(block.blockKey)
                    block.cards.isNotEmpty() && block.isNewerThan(existing) && local.activate(block)
                }.getOrDefault(false)
                if (mirrored) applied++
            }
            val next = page.nextCursor ?: return@withContext applied
            cursorStore.save(next)
            cursor = next
            pages++
            if (page.blocks.size < bounded) return@withContext applied
        }
        applied
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50

        /** Bounded: a few pages per pass, never the whole collection. */
        const val DEFAULT_MAX_PAGES: Int = 3
    }
}
