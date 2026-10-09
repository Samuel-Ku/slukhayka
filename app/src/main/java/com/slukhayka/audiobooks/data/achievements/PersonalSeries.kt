package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.collections.CollectionMatcher

/**
 * #701 (US33, US34, US36) — the two series awards and the one about ORDER,
 * read from the listener's OWN books.
 *
 * The owner's reading (#701, 2026-10-07) replaced «усі томи світу» with «усі
 * ВЛАСНІ твори серії»: the full membership of a series exists only online
 * (`fetchSeriesBooks` caches it in memory), so a promise about the whole world
 * could never be kept from local data. What CAN be proven locally is that
 * every own book of the series was finished — which is exactly what the cycles
 * shelf already counts (`PersonalCycles.finished`), and this rule reuses its
 * series identity (the normalized title, ADR-0012) rather than inventing a
 * second one.
 *
 * Completion is the recorded end-of-book event ([OwnLibraryBook.completedAt]),
 * never `playback_progress.isCompleted`: ADR-0060 accepts only the event, and a
 * hand-set mark must not finish a series.
 */
object PersonalSeries {

    /**
     * How many series the listener owns and has finished ENTIRELY.
     *
     * The unit is the series, and a series is finished when every own book of
     * it carries a completion. One unfinished own volume keeps the whole series
     * open — that is what "all own works of the series" means, and anything
     * weaker would count a series the listener never reached the end of.
     */
    fun finished(books: List<OwnLibraryBook>): Long = groups(books)
        .count { (_, members) -> members.all { it.completedAt != null } }
        .toLong()

    /**
     * How many series whose own NUMBERED tomes were finished in numeric order.
     *
     * «Нагорода про порядок, а не про повноту» (#701): the reading is not
     * "every volume exists" but "the volumes were taken in order", so the rule
     * sorts the finished numbered tomes by their completion instant and asks
     * whether `works.seriesIndex` rises with it. A tome without a number is
     * NOT counted — an unknown position cannot prove an order (ADR-0014) — and
     * a tome the listener never finished has no instant to sort by, so it is
     * not part of the sequence either.
     *
     * [MIN_ORDERED_TOMES] is the definition of "in order", not a threshold:
     * one tome is not a sequence, so nothing about order can be proven from it,
     * and opening the award there would celebrate a single finished book as
     * «По порядку». Two tomes finished in the same millisecond are not ordered
     * either — the comparison is strict, so an unprovable order stays unproven.
     */
    fun inOrder(books: List<OwnLibraryBook>): Long = groups(books)
        .count { (_, members) -> ordered(members) }
        .toLong()

    /** An order needs at least two tomes to exist. */
    const val MIN_ORDERED_TOMES = 2

    private fun ordered(members: List<OwnLibraryBook>): Boolean {
        val tomes = members
            .filter { it.seriesIndex != null && it.completedAt != null }
            .sortedBy { it.completedAt }
        if (tomes.size < MIN_ORDERED_TOMES) return false
        return tomes.zipWithNext().all { (earlier, later) ->
            earlier.completedAt!! < later.completedAt!! && earlier.seriesIndex!! < later.seriesIndex!!
        }
    }

    /** The ONE series identity — the cycles shelf's normalized title (ADR-0012). */
    private fun groups(books: List<OwnLibraryBook>): Map<String, List<OwnLibraryBook>> =
        books.filter { !it.seriesTitle.isNullOrBlank() }
            .groupBy { CollectionMatcher.normalizeTitle(it.seriesTitle!!) }
}
