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

    /**
     * #703 (T5) — the TIMES of real completions.
     *
     * Only timestamps come back; whether one falls at night, or on a holiday,
     * is decided in Kotlin against the listener's own time zone. Doing it in
     * SQL would mean `strftime(..., 'localtime')`, whose meaning depends on the
     * process time zone and cannot be pinned in a test.
     */
    @Query("SELECT timestamp FROM playback_events WHERE kind='COMPLETED'")
    fun observeCompletionTimes(): Flow<List<Long>>

    /** #703 (T5) — the times sessions STARTED, for the owl-and-lark pair. */
    @Query("SELECT timestamp FROM playback_events WHERE kind='RESUME'")
    fun observeSessionStartTimes(): Flow<List<Long>>

    /**
     * #703 (T5) — «Старовинна»: books finished at least a YEAR after they were
     * added to the library.
     *
     * Both ends are historical facts (the completion's timestamp and the
     * entry's `createdAt`), so this needs no "now" and cannot drift as time
     * passes — a book that qualified yesterday still qualifies tomorrow.
     */
    @Query(
        "SELECT COUNT(*) FROM playback_events e " +
            "JOIN library_entries le ON le.id = e.bookId " +
            "WHERE e.kind='COMPLETED' AND (e.timestamp - le.createdAt) > 31536000000"
    )
    fun observeVintageCompletions(): Flow<Long>

    /**
     * #703 (T5) — «Перерва»: books the listener came BACK to after six months.
     *
     * A gap is a property of two consecutive sessions, so this asks whether any
     * LATER session exists that is more than 180 days after an earlier one —
     * MIN/MAX span would not do, since many sessions close together can span
     * months without a single long break.
     */
    @Query(
        "SELECT COUNT(DISTINCT a.bookId) FROM playback_events a WHERE a.kind='RESUME' AND EXISTS (" +
            "SELECT 1 FROM playback_events b WHERE b.bookId = a.bookId AND b.kind='RESUME' " +
            "AND b.timestamp > a.timestamp AND (b.timestamp - a.timestamp) > 15552000000)"
    )
    fun observeReturnsAfterBreak(): Flow<Long>

    /**
     * #704 (T6) — distinct series the listener has books from.
     *
     * `seriesTitle` is a real property of the Work, so this needs no claim to
     * be true. Works with no series are excluded rather than counted as one
     * nameless series.
     */
    @Query(
        "SELECT COUNT(DISTINCT w.seriesTitle) FROM works w " +
            "JOIN library_entries le ON le.workId = w.id " +
            "WHERE w.seriesTitle IS NOT NULL AND w.seriesTitle != ''"
    )
    fun observeSeriesInLibrary(): Flow<Long>

    /**
     * #702 (T4) — library Works per normalized genre.
     *
     * The two rules the ticket sets are both in this SQL:
     *
     *  - **A genre is only ever a real claim.** The row comes from
     *    `work_genres`, which is written from source genre documents. A Work
     *    nobody claimed a genre for simply has no row, so it adds nothing —
     *    no guessing (ADR-0014).
     *  - **Aggregated at Work level.** `work_genres` is keyed by `workId` (plus
     *    sourceId), so one genre claimed by two sources for the same Work is
     *    still ONE book. `COUNT(DISTINCT workId)` is what makes that true.
     *
     * The join to `library_entries` restricts the count to Works the listener
     * actually has.
     */
    @Query(
        "SELECT wg.genreId AS genreId, COUNT(DISTINCT wg.workId) AS works " +
            "FROM work_genres wg JOIN library_entries le ON le.workId = wg.workId " +
            "GROUP BY wg.genreId"
    )
    fun observeGenreBookCounts(): Flow<List<com.slukhayka.audiobooks.data.achievements.GenreBookCount>>

    /**
     * #701 (T3) — books imported through the BROWSER door.
     *
     * Capped at 1: the award marks having taken the harder path at all, and the
     * evaluator's threshold is 1, so a larger count would carry no more truth.
     * The `type` is what `LibraryImport.importSubmittedYouTube` writes.
     */
    @Query("SELECT MIN(COUNT(*), 1) FROM sources WHERE type = 'youtube'")
    fun observeBrowserBooks(): Flow<Long>

    /**
     * #701 (T3) — distinct LANGUAGES the listener has renditions in.
     *
     * Mirrors the established language query in `AudiobookDao` (the First
     * Language Choice): both tables, `!= ''` so an UNKNOWN language is not
     * counted as one, and codes are stored normalized (BCP-47) per CONTEXT.md,
     * so `en` and `English` cannot both appear.
     */
    @Query(
        "SELECT COUNT(DISTINCT language) FROM (" +
            "SELECT language FROM edition_facets WHERE language != '' " +
            "UNION SELECT language FROM editions WHERE language != '')"
    )
    fun observeKnownLanguages(): Flow<Long>

    /**
     * #701 (T3) — distinct source DOORS the listener actually used.
     *
     * Distinct on `type`, not on the row: two books from the same source are
     * one door. A source row only exists once a book was imported through it,
     * so this counts use rather than availability.
     */
    @Query("SELECT COUNT(DISTINCT type) FROM sources")
    fun observeUsedSourceDoors(): Flow<Long>

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
