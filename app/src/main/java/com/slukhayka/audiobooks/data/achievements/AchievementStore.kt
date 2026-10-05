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
    suspend fun claimNotice(knownIds: Set<String>, seenAt: Long): EarnedAchievement?
}
