package com.slukhayka.audiobooks.data.collective

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The last remote collective page fully applied to the local mirror. */
interface CollectiveSyncCursorStore {
    fun load(): CollectiveCursor?
    fun save(cursor: CollectiveCursor)
}

class InMemoryCollectiveSyncCursorStore(
    initial: CollectiveCursor? = null
) : CollectiveSyncCursorStore {
    private var cursor = initial

    override fun load(): CollectiveCursor? = cursor

    override fun save(cursor: CollectiveCursor) {
        this.cursor = cursor
    }
}

/** Durable high-water mark of the collective lane (own prefs, like the facet lane). */
class SharedPreferencesCollectiveSyncCursorStore(context: Context) : CollectiveSyncCursorStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): CollectiveCursor? {
        if (!prefs.contains(KEY_OBSERVED_AT) || !prefs.contains(KEY_DOCUMENT_ID)) return null
        val observedAt = prefs.getLong(KEY_OBSERVED_AT, -1L)
        val documentId = prefs.getString(KEY_DOCUMENT_ID, null).orEmpty()
        return CollectiveCursor(observedAt, documentId)
            .takeIf { it.observedAt >= 0L && it.documentId.isNotBlank() }
    }

    override fun save(cursor: CollectiveCursor) {
        require(cursor.observedAt >= 0L && cursor.documentId.isNotBlank())
        check(
            prefs.edit()
                .putLong(KEY_OBSERVED_AT, cursor.observedAt)
                .putString(KEY_DOCUMENT_ID, cursor.documentId)
                .commit()
        ) { "Collective sync cursor was not persisted" }
    }

    companion object {
        internal const val PREFS_NAME = "collective_sync_cursor"
        private const val KEY_OBSERVED_AT = "observed_at"
        private const val KEY_DOCUMENT_ID = "document_id"
    }
}

/**
 * #522 / ADR-0041 — the bounded cursor delta of the collective catalogue: it
 * walks ordered pages, applies each accepted card to the local mirror through
 * the ordinary seam, and only then advances the durable cursor. A failing
 * page read leaves the cursor where it was (the next pass retries); a failing
 * single card never aborts the page or the cursor — the lane is best-effort by
 * contract and never throws.
 */
class CollectiveDeltaSync(
    private val store: CollectiveCardStore?,
    private val apply: suspend (CollectiveCardPublication) -> Boolean,
    private val cursorStore: CollectiveSyncCursorStore,
    private val maxPages: Int = DEFAULT_MAX_PAGES
) {

    /**
     * @return how many cards the pass actually mirrored this run.
     */
    suspend fun syncOnce(pageSize: Int = DEFAULT_PAGE_SIZE): Int = withContext(Dispatchers.IO) {
        val store = store ?: return@withContext 0
        val bounded = CollectivePageLimits.bounded(pageSize)
        if (bounded <= 0) return@withContext 0
        var cursor = cursorStore.load()
        var applied = 0
        var pages = 0
        while (pages < maxPages.coerceAtLeast(1)) {
            val page = runCatching { store.getCardsPage(cursor, bounded) }
                .getOrElse { return@withContext applied }
            if (page.cards.isEmpty() && page.nextCursor == null) return@withContext applied
            // A page that does not move the cursor carries nothing new (the
            // transport pages strictly after the cursor): stop instead of
            // re-applying the same documents.
            if (cursor != null && page.nextCursor == cursor) return@withContext applied
            for (card in page.cards) {
                if (runCatching { apply(card) }.getOrDefault(false)) applied++
            }
            val next = page.nextCursor ?: return@withContext applied
            // A terminal/short page still commits its high-water mark.
            cursorStore.save(next)
            cursor = next
            pages++
            if (page.cards.size < bounded) return@withContext applied
        }
        applied
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50

        /** Bounded: a few pages per pass, never the whole collection. */
        const val DEFAULT_MAX_PAGES: Int = 5
    }
}
