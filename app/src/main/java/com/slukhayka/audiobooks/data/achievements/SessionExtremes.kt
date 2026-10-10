package com.slukhayka.audiobooks.data.achievements

/**
 * #1183 (T9b) — «Синхронно» and «Літак» in one row: the longest session ever,
 * and the longest session measured by its OFFLINE millis.
 *
 * Two answers from one query rather than two: both are aggregates over the same
 * unindexed `playback_sessions` table, which Room re-reads on every written
 * tick, so the second MAX would be a second full scan for the same invalidation
 * (see `AchievementDao.observeLongestSessions`).
 */
data class SessionExtremes(
    val longestSessionMillis: Long,
    val longestOfflineSessionMillis: Long
)
