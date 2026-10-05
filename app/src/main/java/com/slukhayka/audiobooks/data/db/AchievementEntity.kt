package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** #699 — private local awards; deliberately absent from sync payloads. */
@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val earnedAt: Long,
    val seenAt: Long? = null,
    /**
     * #704 (T6) — when this award was put on the showcase, or null if it is
     * not there. Ordering by this value is what makes «up to 3» meaningful:
     * the newest pin is the one a fourth replaces.
     */
    val pinnedAt: Long? = null
)

/** An observed action, never reconstructed from missing history. */
@Entity(tableName = "achievement_facts")
data class AchievementFactEntity(@PrimaryKey val key: String, val observedAt: Long)
