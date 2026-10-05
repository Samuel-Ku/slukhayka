package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The only progress-reading boundary used by the achievements module. */
fun interface AchievementProgressSource {
    fun observe(): Flow<AchievementProgress>
}

/** Facts with no recoverable historical aggregate; only real accepted events write them. */
enum class AchievementFact { PLAYBACK_STARTED, REVIEW_ACCEPTED, SEARCH_IMPORTED, OFFLINE_PLAYBACK_STARTED, BOOK_COMPLETED, NOT_INTERESTED_CHOSEN }

class LocalAchievementProgressSource(
    private val aggregates: Flow<AchievementProgress>,
    private val facts: Flow<Set<AchievementFact>>
) : AchievementProgressSource {
    override fun observe(): Flow<AchievementProgress> = combine(aggregates, facts) { snapshot, observed ->
        fun count(current: Long, fact: AchievementFact): Long = maxOf(current, if (fact in observed) 1L else 0L)
        snapshot.copy(
            notInterestedChoices = count(snapshot.notInterestedChoices, AchievementFact.NOT_INTERESTED_CHOSEN),
            playbackStarts = count(snapshot.playbackStarts, AchievementFact.PLAYBACK_STARTED),
            acceptedReviews = count(snapshot.acceptedReviews, AchievementFact.REVIEW_ACCEPTED),
            searchImports = count(snapshot.searchImports, AchievementFact.SEARCH_IMPORTED),
            offlinePlaybackStarts = count(snapshot.offlinePlaybackStarts, AchievementFact.OFFLINE_PLAYBACK_STARTED),
            completedBooks = count(snapshot.completedBooks, AchievementFact.BOOK_COMPLETED)
        )
    }
}
