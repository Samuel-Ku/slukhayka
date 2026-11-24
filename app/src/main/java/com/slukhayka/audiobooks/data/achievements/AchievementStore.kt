package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.flow.Flow

data class EarnedAchievement(val id: String, val earnedAt: Long, val seenAt: Long? = null)

/** Local persistence boundary: awards are inserted and notices claimed atomically. */
interface AchievementStore {
    fun observeEarned(): Flow<List<EarnedAchievement>>
    fun observeFacts(): Flow<Set<AchievementFact>>
    suspend fun earned(): List<EarnedAchievement>
    suspend fun award(definitions: List<AchievementDefinition>, earnedAt: Long): List<EarnedAchievement>
    suspend fun recordFact(fact: AchievementFact)

    /**
     * #1173 (T9) — adds exactly one to a durable counter. Repeating the
     * observed action must never lose a count and never double it; the value
     * only grows.
     */
    suspend fun incrementCounter(key: String)
    suspend fun claimNotice(knownIds: Set<String>, seenAt: Long): EarnedAchievement?

    /**
     * ADR-0063 §7 — the startup backfill: mark every pending notice as seen
     * WITHOUT emitting one. Awards already earned (an upgrade, historical
     * progress) are not news, and a queue of old toasts devalues them.
     */
    suspend fun silencePending(knownIds: Set<String>, seenAt: Long)

    /**
     * ADR-0063 §7 — award the historical snapshot already SEEN, in one write:
     * no window where a freshly inserted award is briefly claimable as a
     * notice. The default keeps non-Room stores correct; Room overrides it
     * with a single insert that carries `seenAt`.
     */
    suspend fun awardSilently(
        definitions: List<AchievementDefinition>,
        earnedAt: Long,
        seenAt: Long
    ) {
        award(definitions, earnedAt)
        silencePending(definitions.map { it.id }.toSet(), seenAt)
    }
}
