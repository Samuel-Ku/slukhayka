package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** #699 — private local awards; deliberately absent from sync payloads. */
@Entity(tableName = "achievements")
data class AchievementEntity(@PrimaryKey val id: String, val earnedAt: Long, val seenAt: Long? = null)

/** An observed action, never reconstructed from missing history. */
@Entity(tableName = "achievement_facts")
data class AchievementFactEntity(@PrimaryKey val key: String, val observedAt: Long)
