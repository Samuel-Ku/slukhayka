package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.db.AchievementDao
import com.slukhayka.audiobooks.data.db.AchievementEntity
import com.slukhayka.audiobooks.data.db.AchievementFactEntity
import kotlinx.coroutines.flow.map

class RoomAchievementStore(private val dao: AchievementDao, private val now: () -> Long = System::currentTimeMillis) : AchievementStore, AchievementShowcaseStore {
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

    /** #1173 (T9) — one durable step per observed action (see [AchievementCounter]). */
    override suspend fun incrementCounter(key: String) { dao.incrementCounter(key) }

    override suspend fun claimNotice(knownIds: Set<String>, seenAt: Long) = dao.claimNotice(knownIds, seenAt)?.external()

    // --- #704 (T6) showcase -------------------------------------------------

    override fun observeShowcase() = dao.observePinned().map { rows -> rows.map { it.external() } }

    /**
     * Rewrites the whole showcase from the rule's answer, oldest first.
     *
     * The timestamps step forward by one so the ORDER survives the round trip:
     * marking every kept id with the same `now()` would make «newest first»
     * meaningless, and the fourth pin would evict an arbitrary award instead of
     * the oldest one.
     */
    private suspend fun rewriteShowcase(keep: List<String>) {
        dao.clearPinned()
        val base = now()
        keep.forEachIndexed { index, id -> dao.markPinned(listOf(id), base + index) }
    }

    override suspend fun pin(id: String) = rewriteShowcase(AchievementShowcase.afterPin(dao.pinnedIds(), id))

    override suspend fun unpin(id: String) = rewriteShowcase(AchievementShowcase.afterUnpin(dao.pinnedIds(), id))
    private fun AchievementEntity.external() = EarnedAchievement(id, earnedAt, seenAt)
}
