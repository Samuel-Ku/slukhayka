package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

class AchievementEngine(
    private val progress: AchievementProgressSource,
    private val store: AchievementStore,
    private val onFailure: (Throwable) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis
) {
    /**
     * ADR-0063 §7 — the first snapshot after process start is HISTORICAL: it
     * may award everything the listener already earned before this build, and
     * announcing that queue is exactly the spam that devalues the awards. The
     * first successful evaluation awards and silences; every later snapshot is
     * caused by a live action and gets its quiet one-line notice.
     */
    @Volatile
    var backfilled: Boolean = false
        private set
    val earned get() = store.observeEarned()

    /**
     * #704 (T6) — the latest progress snapshot.
     *
     * Exposed so the achievements screen can derive the TITLE without reaching
     * into the engine's internals or re-reading the database itself.
     */
    val snapshot get() = progress.observe().distinctUntilChanged()
    fun start(scope: CoroutineScope): Job = progress.observe().distinctUntilChanged()
        .onEach { snapshot ->
            if (backfilled) {
                evaluateSnapshot(snapshot)
            } else {
                // Mark the whole backlog seen — including awards written by an
                // older build — and insert THIS snapshot's awards already
                // seen, so no notice queue survives the upgrade and no race
                // window lets a historical award slip into a toast.
                val at = now()
                store.silencePending(AchievementCatalog.definitions.map { it.id }.toSet(), at)
                store.awardSilently(
                    AchievementEvaluator.evaluate(snapshot, store.earned().map { it.id }.toSet()),
                    earnedAt = at,
                    seenAt = at
                )
                backfilled = true
            }
        }
        .retryWhen { cause, attempt ->
            if (cause is CancellationException) false else {
                onFailure(cause)
                delay(minOf(30_000L, 1_000L * (attempt + 1)))
                true
            }
        }.launchIn(scope)
    suspend fun evaluate(): List<EarnedAchievement> = evaluateSnapshot(progress.observe().first())
    private suspend fun evaluateSnapshot(snapshot: AchievementProgress): List<EarnedAchievement> {
        val alreadyEarned = store.earned().map { it.id }.toSet()
        return store.award(AchievementEvaluator.evaluate(snapshot, alreadyEarned), now())
    }
    suspend fun takeNotice(): EarnedAchievement? = store.claimNotice(AchievementCatalog.definitions.map { it.id }.toSet(), now())
}
