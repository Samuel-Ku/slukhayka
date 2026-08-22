package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.source.SourceIds
import com.slukhayka.audiobooks.data.source.sourceIdForUrl

/**
 * One card of the spec-39 «Ваші цикли» shelf: a Series (cycle) the listener
 * owns at least one Work of, ready to render. [listenedCount] /
 * [totalCount] are the honest «Прослухано X із Y» inputs — the UI hides the
 * line unless both numbers are real (ADR-0014). [finished] marks a cycle
 * whose every owned member is completed; unfinished cycles rank first.
 */
data class PersonalCycle(
    val title: String,
    val url: String,
    val coverImageUrl: String?,
    val listenedCount: Int,
    val totalCount: Int,
    val finished: Boolean
)

/**
 * Spec-39 T1 (#261) — the ONE pure builder behind the «Ваші цикли» shelf
 * (the single new seam of this feature, beside `computeResumeStart` per
 * ADR-0008/ADR-0014). Pure JVM: no Android, no I/O, no Compose.
 *
 * Input is exactly what the screens already read (ADR-0009 shaped rows):
 *
 *  - [libraryBooks] — the listener's copies; every row IS an own signal
 *    (a Library Entry), so ownership needs no extra flag;
 *  - [progress] — the Listening State rows; the latest one per book decides
 *    completion and recency;
 *  - [works] — every locally known Work (library ∪ synced catalogue);
 *    counts the honest Y against the same identity as grouping.
 *
 * Rules:
 *
 *  - **Identity** — members group by the normalized series title (the
 *    MergeKey rule + parenthetical trimming + diacritics fold behind the
 *    collection matcher, ADR-0012), so «Відьмак» ≡ «Відьмак (цикл)»;
 *  - **Openability** — a cycle renders only when some member carries a 4read
 *    series URL (the series page path parses 4read pages only); otherwise it
 *    is omitted — a dead card is worse than an absent one;
 *  - **Canonical URL / display title** — the most frequent spelling among
 *    members, ties to the earliest member's one (deterministic for tests
 *    and UI);
 *  - **Ranking** — unfinished cycles first; within both groups by the most
 *    recent Listening State activity, falling back per member to its Library
 *    Entry creation time;
 *  - **Cap** — [SHELF_LIMIT] cards total, so a huge library cannot balloon
 *    the rail.
 */
object PersonalCycles {

    /** Hard cap of the shelf (spec-39 Р6). */
    const val SHELF_LIMIT = 15

    fun build(
        libraryBooks: List<AudiobookEntity>,
        progress: List<PlaybackProgressEntity>,
        works: List<WorkEntity>
    ): List<PersonalCycle> {
        // The latest Listening State row wins per book (several editions of
        // one rendition-card collapse to their most recent activity).
        val latestByBook = progress.groupBy { it.bookId }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.lastListenedAt } }

        data class Member(
            val book: AudiobookEntity,
            val completed: Boolean,
            // Recency signal: the Listening State activity, else when the
            // entry entered the library.
            val activityAt: Long
        )

        fun memberOf(book: AudiobookEntity): Member = Member(
            book = book,
            completed = latestByBook[book.id]?.isCompleted == true,
            activityAt = latestByBook[book.id]?.lastListenedAt ?: book.createdAt
        )

        /** A built cycle plus its ranking keys, stripped before rendering. */
        data class Ranked(val cycle: PersonalCycle, val finished: Boolean, val activityAt: Long)

        // --- grouping: normalized title -> members --------------------------
        return libraryBooks
            .filter { !it.seriesTitle.isNullOrBlank() }
            .groupBy { CollectionMatcher.normalizeTitle(it.seriesTitle!!) }
            .mapNotNull { (identity, members) ->
                val built = members.map(::memberOf)

                // Openability: at least one member must carry a 4read URL.
                val fourReadUrls = members.mapNotNull { m ->
                    m.seriesUrl?.takeIf { sourceIdForUrl(it) == SourceIds.FOUR_READ }
                }
                if (fourReadUrls.isEmpty()) return@mapNotNull null

                val canonicalUrl =
                    mostFrequentSpelling(fourReadUrls) ?: return@mapNotNull null
                val displayTitle =
                    mostFrequentSpelling(members.mapNotNull { it.seriesTitle }) ?: identity

                // Honest counts: X = owned members actually completed; Y =
                // every distinct locally known Work of this cycle — the union
                // of the catalogue's Works sharing the identity and the
                // members' own Work rows (an import without a Works row is
                // still locally known).
                val listenedCount = built.count { it.completed }
                val knownWorkIds = works.asSequence()
                    .filter { normalizeOrNull(it.seriesTitle) == identity }
                    .map { it.id }
                    .toSet() +
                    members.mapNotNull { it.workId }.toSet()

                // Representative cover: the unfinished member with the
                // freshest activity, else the first member in stable order.
                val representative = built.filter { !it.completed }
                    .maxByOrNull { it.activityAt } ?: built.first()

                Ranked(
                    cycle = PersonalCycle(
                        title = displayTitle,
                        url = canonicalUrl,
                        coverImageUrl = representative.book.coverImageUrl,
                        listenedCount = listenedCount,
                        totalCount = knownWorkIds.size,
                        finished = built.all { it.completed }
                    ),
                    finished = built.all { it.completed },
                    activityAt = built.maxOf { it.activityAt }
                )
            }
            .sortedWith(
                compareBy<Ranked> { it.finished }.thenByDescending { it.activityAt }
            )
            .take(SHELF_LIMIT)
            .map { it.cycle }
    }

    /**
     * The most frequent spelling in first-seen order; strict `>` keeps the
     * earliest spelling on a tie. Null only for an empty input.
     */
    private fun mostFrequentSpelling(values: List<String>): String? {
        if (values.isEmpty()) return null
        val counts = values.groupingBy { it }.eachCount()
        var best = values.first()
        var bestCount = counts.getValue(best)
        for (value in values.distinct()) {
            val count = counts.getValue(value)
            if (count > bestCount) {
                best = value
                bestCount = count
            }
        }
        return best
    }

    private fun normalizeOrNull(seriesTitle: String?): String? =
        seriesTitle?.takeIf { it.isNotBlank() }?.let(CollectionMatcher::normalizeTitle)
}
