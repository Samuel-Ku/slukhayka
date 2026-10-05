package com.slukhayka.audiobooks.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {
    @Query("SELECT COUNT(*) FROM library_entries WHERE origin IN ('EXPLICIT_SAVE','EXPLICIT_IMPORT')")
    fun observeExplicitBooks(): Flow<Long>
    @Query("SELECT COALESCE(SUM(verifiedListenedMillis),0) FROM listening_stats")
    fun observeVerifiedListeningMillis(): Flow<Long>
    @Query("SELECT COUNT(*) FROM recommendation_preferences WHERE kind='HIDE_WORK'")
    fun observeNotInterestedChoices(): Flow<Long>
    @Query("SELECT COUNT(DISTINCT bookId) FROM playback_events WHERE kind='COMPLETED'")
    fun observeCompletedBooks(): Flow<Long>

    /**
     * #700 (T2) — completed books SHORTER than three hours.
     *
     * A real join: the completion events against the book's own duration. A row
     * whose `totalDurationSeconds` is 0 counts in NEITHER band — an unknown
     * duration is not a short book (ADR-0014), and the `> 0` guard is what
     * keeps that honest.
     */
    @Query(
        "SELECT COUNT(DISTINCT e.bookId) FROM playback_events e " +
            "JOIN audiobooks a ON a.id = e.bookId " +
            "WHERE e.kind='COMPLETED' AND a.totalDurationSeconds > 0 " +
            "AND a.totalDurationSeconds < 10800"
    )
    fun observeShortCompletedBooks(): Flow<Long>

    /**
     * #700 (T2) — books heard FASTER than 1.5x.
     *
     * The speed is the stored per-book preference (ADR-0009). A NULL means
     * "use the global default" — it is NOT a claim that this book was heard at
     * 1x, so nulls are excluded from BOTH sides of the ladder rather than
     * guessed (ADR-0014).
     */
    @Query("SELECT COUNT(DISTINCT bookId) FROM playback_progress WHERE preferredSpeed > 1.5")
    fun observeFastBooks(): Flow<Long>

    /** #700 (T2) — books heard SLOWER than 0.75x. Nulls excluded, as above. */
    @Query("SELECT COUNT(DISTINCT bookId) FROM playback_progress WHERE preferredSpeed < 0.75")
    fun observeSlowBooks(): Flow<Long>

    /**
     * #700 (T2) — sleep-timer stops the listener reached.
     *
     * `TIMER_STOP` is a RECORDED event kind the player already writes, so this
     * counts something that happened rather than something we assume.
     */
    @Query("SELECT COUNT(*) FROM playback_events WHERE kind='TIMER_STOP'")
    fun observeTimerStops(): Flow<Long>

    /**
     * #700 (T2) — DISTINCT books the listener came back to.
     *
     * DISTINCT, not rows: hearing chapter 3 twice is still one book you
     * returned to, and counting rows would let a single replayed chapter
     * satisfy the whole ladder.
     */
    @Query("SELECT COUNT(DISTINCT bookId) FROM playback_events WHERE kind='RELISTEN'")
    fun observeRelistens(): Flow<Long>

    /** #700 (T2) — every bookmark the listener placed, notes or not. */
    @Query("SELECT COUNT(*) FROM bookmarks")
    fun observeBookmarks(): Flow<Long>

    /**
     * #700 (T2) — bookmarks that carry a WRITTEN note.
     *
     * `note` is non-null but may be blank, and a blank one is a plain bookmark.
     * Trimming before the emptiness test is what keeps the count honest: a row
     * of spaces is not a note.
     */
    @Query("SELECT COUNT(*) FROM bookmarks WHERE TRIM(note) <> ''")
    fun observeNotes(): Flow<Long>

    /** #700 (T2) — completed books of 30+ hours. */
    @Query(
        "SELECT COUNT(DISTINCT e.bookId) FROM playback_events e " +
            "JOIN audiobooks a ON a.id = e.bookId " +
            "WHERE e.kind='COMPLETED' AND a.totalDurationSeconds >= 108000"
    )
    fun observeEpicCompletedBooks(): Flow<Long>
    @Query("SELECT * FROM series_members")
    fun observeKnownSeriesMemberships(): Flow<List<SeriesMemberEntity>>
    @Query("SELECT s.bookId, s.id AS sourceId, s.type AS sourceType, " +
        "MAX((SELECT COUNT(*) FROM chapters c WHERE c.bookId=s.bookId), " +
        "COALESCE((SELECT b.totalChapters FROM audiobooks b WHERE b.id=s.bookId),0), " +
        "COALESCE((SELECT e.totalChapters FROM editions e WHERE e.id=s.editionId),0)) AS chapterCount, " +
        "t.trackIndex, t.localFilePath, t.isDownloaded FROM sources s LEFT JOIN source_tracks t ON t.sourceId=s.id")
    fun observeDownloadedTracks(): Flow<List<com.slukhayka.audiobooks.data.achievements.DownloadedTrackProof>>

    @Query("SELECT * FROM achievements ORDER BY earnedAt, id")
    fun observeEarned(): Flow<List<AchievementEntity>>
    @Query("SELECT * FROM achievements ORDER BY earnedAt, id")
    suspend fun earned(): List<AchievementEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAwards(rows: List<AchievementEntity>): List<Long>
    @Query("SELECT * FROM achievement_facts")
    fun observeFacts(): Flow<List<AchievementFactEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFact(row: AchievementFactEntity): Long
    @Query("SELECT * FROM achievements WHERE seenAt IS NULL AND id IN (:knownIds) ORDER BY earnedAt, id LIMIT 1")
    suspend fun pendingNotice(knownIds: Set<String>): AchievementEntity?
    @Query("UPDATE achievements SET seenAt = :seenAt WHERE id = :id AND seenAt IS NULL")
    suspend fun markSeen(id: String, seenAt: Long): Int
    @Transaction
    suspend fun claimNotice(knownIds: Set<String>, seenAt: Long): AchievementEntity? {
        val pending = pendingNotice(knownIds) ?: return null
        return if (markSeen(pending.id, seenAt) == 1) pending.copy(seenAt = seenAt) else null
    }
}
