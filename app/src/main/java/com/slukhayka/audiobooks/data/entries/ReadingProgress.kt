package com.slukhayka.audiobooks.data.entries

/**
 * ADR-0046 §5/§6 / spec-54 T16 (#876) — progress, the reading journal and the
 * yearly goal, all in the formats' OWN units.
 *
 * The rule of this file, stated once and never broken: **pages are not minutes
 * and percent is not either**. Different formats are reported SIDE BY SIDE;
 * there is no combined "total progress", because summing them would be fiction
 * (ADR-0014).
 */
data class FormatProgress(
    val format: ReadingFormat,
    val unit: ReadingUnit,
    /** The value of the ACTIVE pass, in [unit]. */
    val value: Int,
    val state: ReadingState,
    /** How many passes of this format were finished — the journal's count. */
    val finishedCount: Int,
    /** Passes of this format, newest first (history included). */
    val passes: List<Readthrough>
) {
    val isActive: Boolean get() = state == ReadingState.IN_PROGRESS || state == ReadingState.PLANNED
}

/**
 * The yearly goal as the ticket demands it be shown: ONE finished pass is one
 * unit of the goal, a re-read counts again, and the formats stay separate
 * because their units cannot be summed.
 */
data class YearlyReadingGoal(
    val year: Int,
    val finishedByFormat: Map<ReadingFormat, Int>
) {
    /** The number of finished passes — countable across formats by COUNT. */
    val totalFinished: Int get() = finishedByFormat.values.sum()
}

object ReadingProgressPolicy {

    /**
     * One row per format the Work is read in, each in its own unit. The active
     * pass (in progress, else planned) fronts its format; earlier passes stay
     * in [FormatProgress.passes].
     */
    fun byFormat(readthroughs: List<Readthrough>): List<FormatProgress> =
        readthroughs.groupBy { it.format }
            .map { (format, passes) ->
                val ordered = passes.sortedByDescending { it.startedAt }
                val active = ordered.firstOrNull { it.state == ReadingState.IN_PROGRESS }
                    ?: ordered.firstOrNull { it.state == ReadingState.PLANNED }
                    ?: ordered.first()
                FormatProgress(
                    format = format,
                    unit = active.units.unit,
                    value = active.units.value,
                    state = active.state,
                    finishedCount = ordered.count { it.state == ReadingState.FINISHED },
                    passes = ordered
                )
            }
            .sortedBy { it.format.ordinal }

    /**
     * @param year the calendar year; a pass counts when it was FINISHED in it
     *   ([Readthrough.finishedAt]).
     */
    fun yearlyGoal(readthroughs: List<Readthrough>, year: Int): YearlyReadingGoal {
        val start = yearStartMillis(year)
        val end = yearStartMillis(year + 1)
        val finished = readthroughs.filter { pass ->
            pass.state == ReadingState.FINISHED &&
                pass.finishedAt != null &&
                pass.finishedAt!! >= start &&
                pass.finishedAt!! < end
        }
        return YearlyReadingGoal(
            year = year,
            finishedByFormat = finished.groupingBy { it.format }.eachCount()
        )
    }

    /**
     * The journal as the listener lived it: newest first, each record keeping
     * the unit it was observed in. No record is converted or dropped.
     */
    fun journal(readthroughs: List<Readthrough>): List<Pair<Readthrough, ReadthroughProgressEntry>> =
        readthroughs.flatMap { pass -> pass.journal.map { pass to it } }
            .sortedByDescending { (_, entry) -> entry.at }

    private fun yearStartMillis(year: Int): Long =
        java.time.LocalDate.of(year, 1, 1)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
}
