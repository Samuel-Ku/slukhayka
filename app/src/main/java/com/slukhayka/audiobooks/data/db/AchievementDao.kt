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

    /**
     * #1166 (T8) — every day the listener listened, for the regularity awards.
     *
     * Deliberately NOT a `GROUP BY` in SQL: streaks, «fullest month» and
     * Mondays need a real calendar, and this module keeps date arithmetic in
     * Kotlin with an injected zone (see the night/holiday awards below). The
     * table holds one row per listening day and is never pruned, so it stays
     * small; a day without listening has no row at all.
     */
    @Query("SELECT * FROM listening_stats")
    fun observeListeningDays(): Flow<List<ListeningStatEntity>>
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
     * #703 (T5) — «Ніколи не пізно»: books FINISHED two years or more after
     * they were first opened.
     *
     * Absolute, like the vintage award: both the completion and the first
     * session are recorded facts, so nothing here depends on the day it runs.
     * The EXISTS asks whether ANY earlier session is two years back, which is
     * exactly "started long ago and finally finished".
     */
    @Query(
        "SELECT COUNT(*) FROM playback_events e WHERE e.kind='COMPLETED' AND EXISTS (" +
            "SELECT 1 FROM playback_events f WHERE f.bookId = e.bookId " +
            "AND (e.timestamp - f.timestamp) > 63072000000)"
    )
    fun observeLateCompletions(): Flow<Long>

    /**
     * #704 (T6) — the showcase, oldest first.
     *
     * Only EARNED awards can be pinned: `pinnedAt` lives on the `achievements`
     * row, so there is no way to showcase something the listener has not got.
     */
    @Query("SELECT id FROM achievements WHERE pinnedAt IS NOT NULL ORDER BY pinnedAt ASC")
    suspend fun pinnedIds(): List<String>

    /** #704 (T6) — the showcase for display, newest first. */
    @Query("SELECT * FROM achievements WHERE pinnedAt IS NOT NULL ORDER BY pinnedAt DESC")
    fun observePinned(): Flow<List<com.slukhayka.audiobooks.data.db.AchievementEntity>>

    @Query("UPDATE achievements SET pinnedAt = NULL")
    suspend fun clearPinned()

    @Query("UPDATE achievements SET pinnedAt = :at WHERE id IN (:ids)")
    suspend fun markPinned(ids: List<String>, at: Long)

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
     * #701 — EVERY completion, with the series of its Work whenever the data
     * can name one: the (book, time, series) triple a run needs.
     *
     * `playback_events.bookId` is the Library Entry id, the Entry points at its
     * Work, and the Work carries `seriesTitle`. Both joins are LEFT joins on
     * purpose: a completion the data cannot tie to a series — no Work row, a
     * NULL title, an empty one — is NOT dropped from the answer. It is a real
     * completion that happened between two volumes, so it has to be able to
     * BREAK a run instead of being invisible to it; a skipped row would let
     * «Відьмак 1-3 → стороння книга → Відьмак 4-5» pass as five in a row.
     * `SeriesRun` reads the blank title as that break (ADR-0014).
     *
     * The ORDER is part of the answer: "consecutive in time" is what the award
     * means, so `timestamp, id` fixes it even when several completions share an
     * instant.
     */
    @Query(
        "SELECT e.bookId AS bookId, e.timestamp AS timestamp, w.seriesTitle AS seriesTitle " +
            "FROM playback_events e " +
            "LEFT JOIN library_entries le ON le.id = e.bookId " +
            "LEFT JOIN works w ON w.id = le.workId " +
            "WHERE e.kind='COMPLETED' " +
            "ORDER BY e.timestamp, e.id"
    )
    fun observeSeriesCompletions(): Flow<List<com.slukhayka.audiobooks.data.achievements.SeriesCompletion>>

    /**
     * #701 — books with a recorded START and the language their rendition
     * claims.
     *
     * `editions.workId` is the book's own id — [EditionEntity.workId], «the
     * audiobooks row id this rendition belongs to» — so the join lands on the
     * book that was actually started rather than on a second identity. Empty
     * claims are dropped in SQL; everything else is mapped through
     * [com.slukhayka.audiobooks.data.LanguageCode] in Kotlin, because the ONE
     * language vocabulary lives there and a raw source label (`English`) must
     * still resolve to the canonical `en`.
     *
     * Only the rendition's OWN claim is read: `edition_facets` is deliberately
     * not joined, so a language known merely from the shared facet says nothing
     * here and cannot open the award.
     */
    @Query(
        "SELECT DISTINCT e.bookId AS bookId, ed.language AS language " +
            "FROM playback_events e JOIN editions ed ON ed.workId = e.bookId " +
            "WHERE e.kind='RESUME' AND ed.language != ''"
    )
    fun observeStartLanguages(): Flow<List<com.slukhayka.audiobooks.data.achievements.BookLanguageClaim>>

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

    /**
     * #1175 (US42) — every library row that came through a source: the book,
     * the door (`sources.type`, the persisted registry id) and WHEN that row
     * arrived.
     *
     * `addedAt` is the arrival of the BOOK, not the appearance of the source:
     * the source's own date is a registry fact (`SourceFacts.appearedOn`,
     * ADR-0038), so no column here carries it and no migration is needed.
     * Nothing is grouped in SQL for the same reason — the registry is Kotlin,
     * so the thirty-day window is decided in
     * [com.slukhayka.audiobooks.data.achievements.NewWave].
     */
    @Query("SELECT s.bookId AS bookId, s.type AS sourceType, s.addedAt AS addedAt FROM sources s")
    fun observeSourceArrivals(): Flow<List<com.slukhayka.audiobooks.data.achievements.SourceArrival>>

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

    /**
     * #700 (T2) — «Друге дихання»: books the listener FINISHED a SECOND time.
     *
     * Narrower than the ticket's «завершення після повернення», and on purpose.
     * Two `COMPLETED` rows on the same book are two listening cycles, because
     * `AudioPlayerManager.loadAndPlayBook` does two things: it allows at most
     * one completion per cycle (`completionLogged`, reset on a fresh load), and
     * it sends a start at the very end of a finished book back to chapter 0 /
     * position 0 as `RELISTEN` instead of a fresh load. The first rule alone
     * would let a re-load at the tail log a second `COMPLETED`; the relisten
     * rule is what makes the second row a second full pass.
     *
     * The log keeps only [PlaybackEventPolicy.DEFAULT_EVENTS_PER_BOOK_SOURCE]
     * rows per (book, source), and a second pass fills the bucket with its own
     * `CHAPTER_CHANGE` rows, so the policy lets the newest
     * [PlaybackEventPolicy.PROTECTED_COMPLETION_EVENTS] completions outlive that
     * cap — without it the first row would be evicted by the very relisten that
     * earns this award.
     *
     * The "`COMPLETED` after `RELISTEN`" reading is deliberately NOT used:
     * «Почати спочатку» writes `RELISTEN` on a book that was never finished, so
     * that pairing would count a restart as a return.
     */
    @Query(
        "SELECT COUNT(DISTINCT e.bookId) FROM playback_events e " +
            "WHERE e.kind='COMPLETED' AND EXISTS (" +
            "SELECT 1 FROM playback_events f WHERE f.bookId = e.bookId " +
            "AND f.kind='COMPLETED' AND f.timestamp < e.timestamp)"
    )
    fun observeBooksFinishedTwice(): Flow<Long>

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

    // --- #1173 (T9) durable counters ---------------------------------------
    // INSERT OR IGNORE + `count = count + 1` rather than an UPSERT clause:
    // minSdk is 24 and SQLite grew UPSERT only in 3.24 (Android 11). The
    // transaction makes the pair one step, so a repeated action adds exactly
    // one and the value never goes down.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCounter(row: AchievementCounterEntity): Long

    @Query("UPDATE achievement_counters SET count = count + 1 WHERE `key` = :key")
    suspend fun bumpCounter(key: String)

    @Transaction
    suspend fun incrementCounter(key: String) {
        insertCounter(AchievementCounterEntity(key))
        bumpCounter(key)
    }

    @Query("SELECT count FROM achievement_counters WHERE `key` = :key")
    suspend fun counter(key: String): Long?

    @Query("SELECT * FROM achievement_counters ORDER BY `key`")
    fun observeCounters(): Flow<List<AchievementCounterEntity>>

    // --- #1183 (T9b) the measurement layer's own columns --------------------
    // Every number below is READ from what #1173 already writes; none of it is
    // derived from something else (ADR-0014). The three day columns start at
    // zero in v54, so a listener whose hours predate the measurement layer
    // cannot open these awards by accident.

    /** #1183 (T9b) — verified millis really played from a local source. */
    @Query("SELECT COALESCE(SUM(offlineListenedMillis),0) FROM listening_stats")
    fun observeOfflineListeningMillis(): Flow<Long>

    /** #1183 (T9b) — verified millis really played on a Cast receiver. */
    @Query("SELECT COALESCE(SUM(castListenedMillis),0) FROM listening_stats")
    fun observeCastListeningMillis(): Flow<Long>

    /** #1183 (T9b) — verified millis written inside the 00:00–04:00 window. */
    @Query("SELECT COALESCE(SUM(nightListenedMillis),0) FROM listening_stats")
    fun observeNightListeningMillis(): Flow<Long>

    /**
     * #1183 (T9b) — the longest session ever and the longest OFFLINE one, in
     * ONE round trip.
     *
     * `playback_sessions` is never pruned (`PlaybackSessionEntity`), which is
     * what makes both maxima monotone — an award built on them cannot be taken
     * back by a later quiet week — and also what makes this a full scan: the
     * table carries no index on either column, and #1183 forbids a migration to
     * add one. The table is invalidated on every written tick, so the scan
     * repeats while the listener plays; the two MAXes share one statement to
     * keep that to a single scan, and the caller collapses equal answers before
     * the snapshot above is rebuilt (`distinctUntilChanged`), the same way the
     * download proof skips its file inspection when its rows did not change.
     */
    @Query(
        "SELECT COALESCE(MAX(verifiedMillis),0) AS longestSessionMillis, " +
            "COALESCE(MAX(offlineMillis),0) AS longestOfflineSessionMillis FROM playback_sessions"
    )
    fun observeLongestSessions(): Flow<com.slukhayka.audiobooks.data.achievements.SessionExtremes>

    /**
     * #1183 (T9b) — when every session STARTED, for «Світанок».
     *
     * Only the instants come back; which morning each one belongs to is decided
     * in Kotlin against the listener's own zone, like the completion times
     * above — SQL `localtime` cannot be pinned in a test.
     *
     * `startedAt` never changes after a row is inserted (only `endedAt` and the
     * millis are updated in place), so a repeat of the same list is the same
     * answer: the caller stops it there instead of re-deriving the mornings on
     * every tick of the session it is watching.
     */
    @Query("SELECT startedAt FROM playback_sessions")
    fun observePlaybackSessionStarts(): Flow<List<Long>>

    /**
     * #1183 (T9b) — one durable counter, zero while it was never stepped.
     *
     * COALESCE on an aggregate rather than a nullable scalar: «never armed» and
     * «armed zero times» are the same fact for the award, and the snapshot field
     * is a number.
     */
    @Query("SELECT COALESCE(MAX(count),0) FROM achievement_counters WHERE `key` = :key")
    fun observeCounter(key: String): Flow<Long>

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
