package com.slukhayka.audiobooks.data.reviews

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.PopularityAssertionEntity
import com.slukhayka.audiobooks.data.metadata.PopularityAssertionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #739 / ADR-0041 — the bounded, TTL'd background pass that fills the local
 * listener aggregate of the library rating from the SHARED reviews.
 *
 * Per due Work it reads the shared review list (one batch call for the whole
 * pass, never per row), reduces the valid 1..5 ratings to one aggregate row
 * (`sum:count`, no contributor identity) and persists it through the existing
 * `popularity_assertions` layer — no schema change, no per-review storage.
 *
 * Honesty rules (ADR-0014/ADR-0022):
 * - a Work whose reviews are absent or all invalid writes nothing — an absent
 *   claim is never recorded as a zero;
 * - a fresh aggregate is skipped (TTL), so the pass is bounded and cheap;
 * - any store/Room failure is silent and leaves the previous projection in
 *   place; stale evidence is never replaced with a guess.
 */
class LibraryRatingRefresh(
    private val dao: AudiobookDao,
    private val reviews: ListenerReviewsStore?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchLimit: Int = DEFAULT_BATCH
) {

    /**
     * @return how many Works received a refreshed aggregate this pass.
     */
    suspend fun refreshIfDue(): Int = withContext(Dispatchers.IO) {
        val store = reviews ?: return@withContext 0
        val now = clock()
        val freshKeys = dao.popularityAssertions(PopularityAssertionEntity.KIND_LISTENER_RATING)
            .filter { PopularityAssertionPolicy.isFresh(it.observedAt, now, TTL_MS) }
            .mapTo(mutableSetOf()) { it.mergeKey }

        val due = dao.getAllAudiobooksOnce()
            .map { it.toAudiobookEntity() }
            .groupBy { rankingKeyOf(it) }
            .filterKeys { it !in freshKeys }
            .mapValues { (_, members) -> members.first() }
            .entries
            .take(batchLimit.coerceAtLeast(1))
        if (due.isEmpty()) return@withContext 0

        // The review store is keyed by the Work id the book page uses.
        val reviewKeys = due.associate { (key, book) ->
            key to book.workId?.takeIf { it.isNotBlank() }.orEmpty().ifBlank { book.id }
        }
        val byWork = runCatching { store.getForWorks(reviewKeys.values.toList()) }
            .getOrElse { return@withContext 0 }

        var updated = 0
        val rows = mutableListOf<PopularityAssertionEntity>()
        for ((key, _) in due) {
            val ratings = byWork[reviewKeys.getValue(key)]
                .orEmpty()
                .mapNotNull { review ->
                    review.rating.takeIf {
                        it in ListenerReviewLimits.MIN_RATING..ListenerReviewLimits.MAX_RATING
                    }
                }
            // A read with no (or only invalid) reviews writes nothing: the
            // reviews seam fails CLOSED to an empty map, so an empty result is
            // indistinguishable from a network failure — the last known
            // aggregate stays (AC: «без мережі показує останні відомі»), and
            // nothing is ever recorded as a zero.
            if (ratings.isEmpty()) continue
            PopularityAssertionPolicy.listenerRatingRecord(key, ratings.sum(), ratings.size, now)
                ?.let { rows += it; updated++ }
        }
        if (rows.isEmpty()) return@withContext 0
        runCatching { dao.upsertPopularityAssertions(rows) }.onFailure { return@withContext 0 }
        updated
    }

    private fun rankingKeyOf(book: com.slukhayka.audiobooks.data.db.AudiobookEntity): String =
        book.mergeKey.ifBlank { book.workId.orEmpty().ifBlank { book.id } }

    companion object {
        /** Shared reviews move slowly; one pass a day per Work is plenty. */
        const val TTL_MS: Long = 24L * 60 * 60 * 1000

        /** Bounded: one batch call and one write set per pass. */
        const val DEFAULT_BATCH: Int = 50
    }
}
