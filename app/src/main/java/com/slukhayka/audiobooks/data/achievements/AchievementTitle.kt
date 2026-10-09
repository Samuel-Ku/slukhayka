package com.slukhayka.audiobooks.data.achievements

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
 */
enum class ListenerTitle(val label: String) {
    LISTENER("Слухач"),
    PAGE_TRAVELLER("Мандрівник сторінками"),
    STORYTELLER("Оповідач"),
    STORY_KEEPER("Хранитель історій"),
    LIBRARY_VOICE("Голос бібліотеки")
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
