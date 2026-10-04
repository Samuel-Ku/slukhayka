package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.db.AchievementDao
import com.slukhayka.audiobooks.data.db.AchievementEntity
import com.slukhayka.audiobooks.data.db.AchievementFactEntity
import kotlinx.coroutines.flow.map

class RoomAchievementStore(private val dao: AchievementDao, private val now: () -> Long = System::currentTimeMillis) : AchievementStore {
    override fun observeEarned() = dao.observeEarned().map { rows -> rows.map { it.external() } }
    override fun observeFacts() = dao.observeFacts().map { rows -> rows.mapNotNull { row -> AchievementFact.entries.firstOrNull { it.name == row.key } }.toSet() }
    override suspend fun earned() = dao.earned().map { it.external() }
    override suspend fun award(definitions: List<AchievementDefinition>, earnedAt: Long): List<EarnedAchievement> {
        if (definitions.isEmpty()) return emptyList()
        val rows = definitions.map { AchievementEntity(it.id, earnedAt) }
        // Room inserts the batch in one transaction; IGNORE identifies exactly
        // the rows this evaluator won, including races with another evaluator.
        val inserted = dao.insertAwards(rows)
        return rows.zip(inserted).filter { (_, rowId) -> rowId != -1L }.map { (row, _) -> row.external() }
    }
    override suspend fun recordFact(fact: AchievementFact) { dao.insertFact(AchievementFactEntity(fact.name, now())) }
    override suspend fun claimNotice(knownIds: Set<String>, seenAt: Long) = dao.claimNotice(knownIds, seenAt)?.external()
    private fun AchievementEntity.external() = EarnedAchievement(id, earnedAt, seenAt)
}
