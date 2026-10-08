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

/**
 * #1173 (T9) — a durable monotonic counter of an observed action that repeats.
 *
 * The first key is the moment the «до кінця розділу» mode was ARMED: at a
 * chapter boundary the timer re-arms for the next chapter and a TIMER_STOP
 * event may never be written, so the armed moment is the only honest fact
 * (#700). A counter is never reset — the awards above it are monotonic.
 */
@Entity(tableName = "achievement_counters")
data class AchievementCounterEntity(@PrimaryKey val key: String, val count: Long = 0L)
