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
}
