package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.db.CorrectionKind
import com.slukhayka.audiobooks.data.db.CorrectionOrigin

/**
 * ADR-0053 / #855 (T2) — the write half of the cover Override: the listener's
 * fix lands on the card through the ordinary cover write path
 * ([AudiobookDao.updateCoverImageUrl] — the same door the import, the search
 * and the resolvers use), AND is remembered in the correction memory, so no
 * later external claim can undo it ([CoverOverride] holds the rule).
 *
 * One statement per decision, no second truth: the row is what the listener
 * sees now, the memory is why it stays that way.
 */
class CoverOverrideStore(private val dao: AudiobookDao) {

    /**
     * Pins [coverUrl] (null = «no cover») for the Work behind [bookId] and
     * remembers the decision. Idempotent: re-pinning the same value replaces
     * the same memory row (PK is mergeKey|kind|value).
     */
    suspend fun pin(
        bookId: String,
        mergeKey: String,
        coverUrl: String?,
        now: Long = System.currentTimeMillis()
    ) {
        val clean = coverUrl?.trim()?.takeIf { it.isNotEmpty() }
        // The row first: the cover the listener sees is the fix itself. A
        // blank value CLEARS the cover (honest absence), never a placeholder.
        dao.updateCoverImageUrl(bookId, clean.orEmpty())
        if (mergeKey.isBlank()) return
        dao.upsertCorrection(
            CorrectionEntity(
                mergeKey = mergeKey,
                kind = CorrectionKind.FIELD,
                value = CoverOverride.encoded(clean),
                origin = CorrectionOrigin.USER_MADE,
                updatedAt = now
            )
        )
    }

    /** The decision pinned to one Work, or null when there is none. */
    suspend fun pinned(mergeKey: String): CoverOverride.Pinned? =
        if (mergeKey.isBlank()) {
            null
        } else {
            CoverOverride.pinned(dao.getCorrectionsForMergeKey(mergeKey))
        }
}
