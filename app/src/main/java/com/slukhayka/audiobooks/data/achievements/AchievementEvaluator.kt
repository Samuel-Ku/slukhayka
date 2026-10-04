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
    val verifiedListeningMillis: Long = 0,
    val registeredSourceIds: Set<String> = emptySet(),
    val knownSeriesMemberships: Set<AchievementSeriesMembership> = emptySet()
)
/** Membership asserted by an existing source/cache; it never implies a complete series. */
data class AchievementSeriesMembership(val seriesId: String, val workId: String, val position: Int)

enum class AchievementMetric {
    EXPLICIT_BOOKS, PLAYBACK_STARTS, ACCEPTED_REVIEWS, NOT_INTERESTED,
    SEARCH_IMPORTS, OFFLINE_PLAYBACK_STARTS, DOWNLOADED_BOOKS, COMPLETED_BOOKS, LISTENING_MILLIS,
    SHORT_COMPLETED_BOOKS, EPIC_COMPLETED_BOOKS;

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
        AchievementDefinition("long_liver_5", "books_shape", 2, AchievementMetric.EPIC_COMPLETED_BOOKS, 5)
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
