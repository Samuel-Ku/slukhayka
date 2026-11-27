package com.slukhayka.audiobooks.data.achievements

import androidx.annotation.StringRes
import com.slukhayka.audiobooks.R

/**
 * #704 (T6) — the listener's title.
 *
 * The spec asks for «титул, що зростає за СИНЕРГІЮ годин, книг, серій і
 * курації» and forbids any component for «час у застосунку» or «кількість
 * тапів». So the ladder is built from four things the listener actually DID,
 * and it is a MINIMUM across them rather than a sum:
 *
 *  - a sum would let 500 hours alone buy the top title, which is exactly the
 *    "time spent" measure the spec rules out;
 *  - a minimum means every rung needs breadth as well as depth — the synergy
 *    the spec names.
 *
 * #1824 — the rung carries a resource, not the words. The Ukrainian label used
 * to live inline in the enum, so the App Locale could not reach it: EN and PL
 * rendered «Title: Слухач». The ladder below still decides WHICH rung; the
 * strings live in `values/`, `values-en/` and `values-pl/`.
 */
enum class ListenerTitle(@StringRes val labelRes: Int) {
    LISTENER(R.string.achievement_title_listener),
    PAGE_TRAVELLER(R.string.achievement_title_page_traveller),
    STORYTELLER(R.string.achievement_title_storyteller),
    STORY_KEEPER(R.string.achievement_title_story_keeper),
    LIBRARY_VOICE(R.string.achievement_title_library_voice)
}

object AchievementTitle {

    private const val HOUR_MS = 3_600_000L

    /**
     * Per-component thresholds, one entry per title rung. Index 0 is always 0:
     * «Слухач» is what you are before doing anything, not an award.
     *
     * The numbers are a deliberate ladder, not measured data — the spec fixes
     * the ORDER and the ingredients, not the values. They live here, in one
     * place, and are pinned by tests so a later change is a decision.
     */
    private val hours = listOf(0L, 10L, 50L, 200L, 500L)
    private val books = listOf(0L, 5L, 25L, 100L, 250L)
    private val series = listOf(0L, 2L, 5L, 15L, 40L)
    private val curation = listOf(0L, 1L, 5L, 20L, 50L)

    fun of(snapshot: AchievementProgress): ListenerTitle {
        val listenedHours = snapshot.verifiedListeningMillis / HOUR_MS
        val reached = listOf(
            rungFor(listenedHours, hours),
            rungFor(snapshot.completedBooks, books),
            rungFor(snapshot.seriesInLibrary, series),
            rungFor(snapshot.acceptedReviews, curation)
        )
        // The WEAKEST component decides: the title is only as high as what the
        // listener has actually done across all four.
        return ListenerTitle.entries[reached.min()]
    }

    private fun rungFor(value: Long, thresholds: List<Long>): Int =
        thresholds.indexOfLast { value >= it }.coerceAtLeast(0)
}
