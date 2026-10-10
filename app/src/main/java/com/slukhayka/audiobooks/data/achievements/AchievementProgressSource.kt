package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The only progress-reading boundary used by the achievements module. */
fun interface AchievementProgressSource {
    fun observe(): Flow<AchievementProgress>
}

/**
 * Facts with no recoverable historical aggregate; only real accepted events write them.
 *
 * #1174 (друга смуга) — [FINISHED_AFTER_ABANDON] joins them, and it is the one
 * fact with an EXPIRY: it is readable only while the book still carries the
 * «покинуто» mark, so it is captured in the same step that takes the mark away
 * (`AbandonedBooks.finish`). A row written later would be a fiction — after the
 * write the pass reads FINISHED and nothing says it ever was abandoned.
 *
 * #701 — [CROSS_RESOLVED] and [SOURCE_RECOVERED] join them for the same reason:
 * neither a successful cross-resolve nor a rescued source leaves anything in
 * Room to read afterwards, so the success point is the only moment that can
 * record them. [SOURCE_RECOVERED] is deliberately ONE key for TWO mechanisms —
 * the stream self-heal and the browser recovery are the same story (a broken
 * source was rescued), and two keys would fire two notices for it.
 */
enum class AchievementFact {
    PLAYBACK_STARTED, REVIEW_ACCEPTED, SEARCH_IMPORTED, OFFLINE_PLAYBACK_STARTED,
    BOOK_COMPLETED, NOT_INTERESTED_CHOSEN,
    FINISHED_AFTER_ABANDON,
    CROSS_RESOLVED, SOURCE_RECOVERED
}

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
            completedBooks = count(snapshot.completedBooks, AchievementFact.BOOK_COMPLETED),
            // #1174 (друга смуга) — «Друге дихання»'s second path. The fact is
            // captured while the mark is still on the pass, so its presence is
            // what proves the book WAS abandoned when the listener finished it.
            booksFinishedAfterAbandon =
                count(snapshot.booksFinishedAfterAbandon, AchievementFact.FINISHED_AFTER_ABANDON),
            // #701 — the two mechanism facts. Both are "it happened at all":
            // the fact table holds one row per key, and the awards ask whether
            // the mechanism ever succeeded, not how many times.
            crossResolves = count(snapshot.crossResolves, AchievementFact.CROSS_RESOLVED),
            sourceRecoveries = count(snapshot.sourceRecoveries, AchievementFact.SOURCE_RECOVERED)
        )
    }
}
