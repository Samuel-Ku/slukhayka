package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.flow.Flow

/**
 * #704 (T6) — which awards the listener chose to show, and the rule that keeps
 * the list short.
 *
 * The spec says «вітрина — до 3 pinned id». That limit is a RULE, so it lives
 * in one pure place and the store applies it — rather than being spread across
 * SQL, the screen and a comment.
 */
object AchievementShowcase {

    const val MAX_PINNED = 3

    /**
     * The ids to keep after pinning [id], oldest first.
     *
     * Pinning something already on the list MOVES it to the front rather than
     * adding a duplicate, and a fourth pin drops the OLDEST — the listener's
     * most recent choice is the one that survives.
     */
    fun afterPin(current: List<String>, id: String): List<String> =
        (current.filterNot { it == id } + id).takeLast(MAX_PINNED)

    /** The ids to keep after unpinning [id]; unpinning something absent is a no-op. */
    fun afterUnpin(current: List<String>, id: String): List<String> =
        current.filterNot { it == id }
}

/**
 * #704 (T6) — the showcase as a boundary of its own.
 *
 * Separate from [AchievementStore] on purpose: showing off an award and earning
 * one are different jobs, and a reader that only displays the showcase should
 * not have to depend on the whole award machinery.
 */
interface AchievementShowcaseStore {
    /** The pinned awards, newest first. */
    fun observeShowcase(): Flow<List<EarnedAchievement>>

    /** Pins an award, applying the «up to 3» rule. */
    suspend fun pin(id: String)

    /** Removes an award from the showcase; absent is a no-op. */
    suspend fun unpin(id: String)
}
