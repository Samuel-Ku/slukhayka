package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * The achievements evaluation engine — a CALLER-PULLED snapshot (#1574).
 *
 * Before the on-demand seam the engine ran a permanent watcher
 * ([start]): every DAO invalidation of the combine tower — a progress write
 * lands roughly every five seconds of playback — recomputed the snapshot and
 * re-ran `evaluate` + `award`. Now the snapshot is owned by
 * [AchievementSnapshot], and [refresh] is the ONE trigger:
 *
 * - app start (the silent backfill, ADR-0063 §7),
 * - each progress-write checkpoint (the recorder queue calls it after a
 *   successful observation/fact/counter write),
 * - a book completion (also a recorder write),
 * - opening the «Досягнення» screen,
 * - and the direct-fact paths (`AbandonedBooks.finish`).
 *
 * A DAO write nobody pulled changes neither the board nor the title. There is
 * deliberately no observer, no throttling and no hot/cold split: callers pull.
 *
 * Failures stay inside the seam: a transient local persistence error is
 * reported through [onFailure] and the refresh returns no awards, exactly the
 * non-fatal behaviour the old retrying watcher had (the next trigger retries).
 */
class AchievementEngine(
    private val source: AchievementSnapshot,
    private val store: AchievementStore,
    private val onFailure: (Throwable) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis
) {
    /**
     * ADR-0063 §7 — the first successful refresh after process start is
     * HISTORICAL: it may award everything the listener already earned before
     * this build, and announcing that queue is exactly the spam that devalues
     * the awards. The first refresh awards and silences; every later one is
     * caused by a live action and gets its quiet one-line notice.
     */
    @Volatile
    var backfilled: Boolean = false
        private set
    val earned get() = store.observeEarned()

    /**
     * #1574 — the latest progress snapshot, owned by [AchievementSnapshot].
     *
     * The achievements screen derives the title and the registry status lines
     * from this StateFlow instead of re-reading the database itself, and it
     * moves only on [refresh].
     */
    val snapshot: StateFlow<AchievementProgress> get() = source.snapshot

    /** Pull-then-evaluate: the ONLY entry point that can change the board. */
    suspend fun refresh(): List<EarnedAchievement> = try {
        val current = source.refresh()
        if (backfilled) {
            evaluateSnapshot(current)
        } else {
            // Mark the whole backlog seen — including awards written by an
            // older build — and insert THIS snapshot's awards already seen, so
            // no notice queue survives the upgrade and no race window lets a
            // historical award slip into a toast.
            val at = now()
            store.silencePending(AchievementCatalog.definitions.map { it.id }.toSet(), at)
            store.awardSilently(
                AchievementEvaluator.evaluate(current, store.earned().map { it.id }.toSet()),
                earnedAt = at,
                seenAt = at
            )
            backfilled = true
            emptyList()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // A transient local persistence failure must not disable later earning:
        // the next explicit refresh (the next progress checkpoint) retries.
        onFailure(failure)
        emptyList()
    }

    /**
     * Evaluate the CURRENT snapshot without forcing a fresh database pass.
     *
     * Kept for callers that have just refreshed and need the award list again;
     * it never becomes a second trigger.
     */
    suspend fun evaluate(): List<EarnedAchievement> = evaluateSnapshot(source.snapshot.value)

    suspend fun takeNotice(): EarnedAchievement? = store.claimNotice(AchievementCatalog.definitions.map { it.id }.toSet(), now())

    private suspend fun evaluateSnapshot(snapshot: AchievementProgress): List<EarnedAchievement> {
        val alreadyEarned = store.earned().map { it.id }.toSet()
        return store.award(AchievementEvaluator.evaluate(snapshot, alreadyEarned), now())
    }
}
