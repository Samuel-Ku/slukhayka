package com.slukhayka.audiobooks.data.achievements

data class AchievementProgress(
    val explicitBooks: Long = 0,
    val playbackStarts: Long = 0,
    val acceptedReviews: Long = 0,
    val notInterestedChoices: Long = 0,
    val searchImports: Long = 0,
    val offlinePlaybackStarts: Long = 0,
    val downloadedBooks: Long = 0,
    val completedBooks: Long = 0,
    /** #700 — completed books SHORTER than three hours («Коротка форма»). */
    val shortCompletedBooks: Long = 0,
    /** #700 — completed books of 30+ hours. */
    val epicCompletedBooks: Long = 0,
    /** #700 — books the listener chose to hear FASTER than 1.5x. */
    val fastBooks: Long = 0,
    /** #700 — books the listener chose to hear SLOWER than 0.75x. */
    val slowBooks: Long = 0,
    /** #700 — bookmarks the listener placed. */
    val bookmarks: Long = 0,
    /** #700 — bookmarks that actually carry a written note (non-blank). */
    val notes: Long = 0,
    /** #700 — sleep-timer stops the listener actually reached. */
    val timerStops: Long = 0,
    /** #700 — books the listener came BACK to and heard again. */
    val relistens: Long = 0,
    /**
     * #701 (T3) — how many DIFFERENT source doors the listener actually went
     * through.
     *
     * Not the same as [registeredSourceIds]: that is the set the app OFFERS.
     * This counts sources a library row actually points at, i.e. doors the
     * listener used.
     */
    val usedSourceDoors: Long = 0,
    val verifiedListeningMillis: Long = 0,
    val registeredSourceIds: Set<String> = emptySet(),
    val knownSeriesMemberships: Set<AchievementSeriesMembership> = emptySet()
)
/** Membership asserted by an existing source/cache; it never implies a complete series. */
data class AchievementSeriesMembership(val seriesId: String, val workId: String, val position: Int)

enum class AchievementMetric {
    EXPLICIT_BOOKS, PLAYBACK_STARTS, ACCEPTED_REVIEWS, NOT_INTERESTED,
    SEARCH_IMPORTS, OFFLINE_PLAYBACK_STARTS, DOWNLOADED_BOOKS, COMPLETED_BOOKS, LISTENING_MILLIS,
    SHORT_COMPLETED_BOOKS, EPIC_COMPLETED_BOOKS, FAST_BOOKS, SLOW_BOOKS, BOOKMARKS, NOTES,
    TIMER_STOPS, RELISTENS, USED_SOURCE_DOORS;

    fun value(snapshot: AchievementProgress): Long = when (this) {
        EXPLICIT_BOOKS -> snapshot.explicitBooks
        PLAYBACK_STARTS -> snapshot.playbackStarts
        ACCEPTED_REVIEWS -> snapshot.acceptedReviews
        NOT_INTERESTED -> snapshot.notInterestedChoices
        SEARCH_IMPORTS -> snapshot.searchImports
        OFFLINE_PLAYBACK_STARTS -> snapshot.offlinePlaybackStarts
        DOWNLOADED_BOOKS -> snapshot.downloadedBooks
        COMPLETED_BOOKS -> snapshot.completedBooks
        SHORT_COMPLETED_BOOKS -> snapshot.shortCompletedBooks
        EPIC_COMPLETED_BOOKS -> snapshot.epicCompletedBooks
        FAST_BOOKS -> snapshot.fastBooks
        SLOW_BOOKS -> snapshot.slowBooks
        BOOKMARKS -> snapshot.bookmarks
        NOTES -> snapshot.notes
        TIMER_STOPS -> snapshot.timerStops
        RELISTENS -> snapshot.relistens
        USED_SOURCE_DOORS -> snapshot.usedSourceDoors
        LISTENING_MILLIS -> snapshot.verifiedListeningMillis
    }
}

data class AchievementDefinition(
    val id: String,
    val group: String,
    val level: Int,
    val metric: AchievementMetric,
    val threshold: Long,
    val hidden: Boolean = false
)

object AchievementCatalog {
    val definitions: List<AchievementDefinition> = listOf(
        AchievementDefinition("first_book", "first_steps", 1, AchievementMetric.EXPLICIT_BOOKS, 1),
        AchievementDefinition("first_playback", "first_steps", 1, AchievementMetric.PLAYBACK_STARTS, 1),
        AchievementDefinition("first_review", "first_steps", 1, AchievementMetric.ACCEPTED_REVIEWS, 1),
        AchievementDefinition("first_not_interested", "first_steps", 1, AchievementMetric.NOT_INTERESTED, 1),
        AchievementDefinition("first_search_import", "first_steps", 1, AchievementMetric.SEARCH_IMPORTS, 1),
        AchievementDefinition("first_offline_playback", "first_steps", 1, AchievementMetric.OFFLINE_PLAYBACK_STARTS, 1),
        AchievementDefinition("first_download", "first_steps", 1, AchievementMetric.DOWNLOADED_BOOKS, 1),
        AchievementDefinition("first_completion", "first_steps", 1, AchievementMetric.COMPLETED_BOOKS, 1)
    ) + listOf(1L, 10L, 100L, 1000L, 5000L).mapIndexed { index, hours ->
        AchievementDefinition("hours_$hours", "hours", index + 1, AchievementMetric.LISTENING_MILLIS, hours * 3_600_000L)
    } + listOf(1L, 5L, 10L, 25L, 50L, 100L, 250L, 500L).mapIndexed { index, books ->
        // #700 (T2) — the book path. Thresholds are the spec's, verbatim; the
        // metric is the SAME real completed count T1 already reads
        // (`observeCompletedBooks`), so nothing here is inferred.
        AchievementDefinition("books_$books", "books", index + 1, AchievementMetric.COMPLETED_BOOKS, books)
    } + listOf(
        // #700 (T2) — shape of the reading, not just its size. Both bands come
        // from a real join: completed `playback_events` against the book's own
        // `totalDurationSeconds`. A book whose duration is unknown (0) counts
        // in NEITHER band — a guess is not a fact (ADR-0014).
        AchievementDefinition("short_form_10", "books_shape", 1, AchievementMetric.SHORT_COMPLETED_BOOKS, 10),
        AchievementDefinition("epic_1", "books_shape", 1, AchievementMetric.EPIC_COMPLETED_BOOKS, 1),
        AchievementDefinition("long_liver_5", "books_shape", 2, AchievementMetric.EPIC_COMPLETED_BOOKS, 5),
        // #700 (T2) — HOW the listener listens. The speed is the stored
        // per-book preference (ADR-0009); a book with NO stored preference is
        // not counted at all, because "no preference" is the global default,
        // not a claim about this book (ADR-0014).
        AchievementDefinition("speedster_10", "habits", 1, AchievementMetric.FAST_BOOKS, 10),
        AchievementDefinition("slow_savour_5", "habits", 1, AchievementMetric.SLOW_BOOKS, 5),
        // #700 (T2) — marking your place, and writing something there. A
        // bookmark with a blank note is a bookmark, NOT a note: the two counts
        // stay apart so neither award can claim something the listener did not
        // do (ADR-0014).
        AchievementDefinition("bookmarks_50", "habits", 2, AchievementMetric.BOOKMARKS, 50),
        AchievementDefinition("notes_10", "habits", 3, AchievementMetric.NOTES, 10),
        // #700 (T2) — falling asleep to a book, and coming back to one. Both
        // are RECORDED events (`TIMER_STOP`, `RELISTEN`), not inferred from
        // anything else — the app already writes them.
        AchievementDefinition("sleep_timer_20", "habits", 4, AchievementMetric.TIMER_STOPS, 20),
        AchievementDefinition("relisten_1", "relisten", 1, AchievementMetric.RELISTENS, 1),
        AchievementDefinition("relisten_5", "relisten", 2, AchievementMetric.RELISTENS, 5),
        // #700 (T2) — «Глибокий запас»: ten books actually downloaded for
        // offline use. Built on the same real proof T1 already uses for
        // `first_download` (a track row that is downloaded AND whose file is
        // really on disk), so a row alone cannot claim it.
        //
        // The other three offline awards («Автономний», «Літак», «Гурман
        // завантажень») need offline HOURS, which nothing records yet — they
        // are deliberately absent rather than approximated from the count of
        // offline starts, which is a different fact.
        AchievementDefinition("deep_reserve_10", "offline", 1, AchievementMetric.DOWNLOADED_BOOKS, 10),
        // #701 (T3) — «Чотири двері»: listening from four DIFFERENT sources.
        // Counts doors the listener actually used (library rows), never the
        // set the app merely offers — those are different facts.
        AchievementDefinition("four_doors", "doors", 1, AchievementMetric.USED_SOURCE_DOORS, 4)
    )
}

object AchievementEvaluator {
    fun evaluate(
        snapshot: AchievementProgress,
        alreadyEarned: Set<String>,
        catalog: List<AchievementDefinition> = AchievementCatalog.definitions
    ): List<AchievementDefinition> = catalog.filter {
        it.id !in alreadyEarned && it.metric.value(snapshot) >= it.threshold
    }
}
