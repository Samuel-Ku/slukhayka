package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.source.SourceIds
import com.slukhayka.audiobooks.data.source.sourceIdForUrl

/**
 * One card of the spec-39 «Ваші цикли» shelf: a Series (cycle) the listener
 * owns at least one Work of, ready to render.
 *
 * Count honesty (ADR-0014): [listenedCount] / [totalCount] carry real
 * numbers ONLY on own cards ([reasonTitle] == null) — the UI hides the line
 * unless both are real. Similar-tier cards ([reasonTitle] != null) mean the
 * listener owns nothing there: the UI must render the reason chip instead
 * of progress, never the count fields.
 */
data class PersonalCycle(
    val title: String,
    val url: String,
    val coverImageUrl: String?,
    val listenedCount: Int,
    val totalCount: Int,
    val finished: Boolean,
    val reasonTitle: String? = null
)

/**
 * Spec-39 (#261/#262) — the ONE pure builder behind the «Ваші цикли» shelf
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
 *    counts the honest Y against the same identity as grouping;
 *  - [recommendations] — the ranked output of the existing on-device engine
 *    (spec-19); each candidate (whose id is the Work merge key) lifts into
 *    the similar tier, T2.
 *
 * Rules:
 *
 *  - **Identity** — members group by the normalized series title (the
 *    MergeKey rule + parenthetical trimming + diacritics fold behind the
 *    collection matcher, ADR-0012), so «Відьмак» ≡ «Відьмак (цикл)»;
 *  - **Openability** — a cycle renders only when some Work of its identity
 *    group carries a 4read series URL (the series page path parses 4read
 *    pages only); otherwise it is omitted — a dead card is worse than an
 *    absent one;
 *  - **Canonical URL / display title** — the most frequent spelling within
 *    the identity group, ties to the earliest one (deterministic for tests
 *    and UI);
 *  - **Own ranking** — unfinished cycles first; within both groups by the
 *    most recent Listening State activity, falling back per member to its
 *    Library Entry creation time;
 *  - **Similar tier (T2)** — engine picks lift through their Work's series
 *    identity, in engine order (score-ranked); own identities are never
 *    suggested — including owned cycles omitted from the shelf for lacking
 *    an openable URL; duplicate lifts collapse keeping the strongest reason;
 *    best-effort by construction — empty picks simply yield no tier;
 *  - **Cap** — [SHELF_LIMIT] cards across BOTH tiers, own first.
 */
object PersonalCycles {

    /** Hard cap of the shelf (spec-39 Р6). */
    const val SHELF_LIMIT = 15

    fun build(
        libraryBooks: List<AudiobookEntity>,
        progress: List<PlaybackProgressEntity>,
        works: List<WorkEntity>,
        recommendations: List<RecommendationEngine.Recommendation> = emptyList()
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
        val ownedBooks = libraryBooks.filter { !it.seriesTitle.isNullOrBlank() }
        val ownGroups = ownedBooks.groupBy { CollectionMatcher.normalizeTitle(it.seriesTitle!!) }

        val ownCycles = ownGroups.mapNotNull { (identity, members) ->
            val built = members.map(::memberOf)

            // Openability: at least one member must carry a 4read URL.
            val fourReadUrls = fourReadUrlsOf(members.map { it.seriesUrl })
            if (fourReadUrls.isEmpty()) return@mapNotNull null

            val canonicalUrl =
                mostFrequentSpelling(fourReadUrls) ?: return@mapNotNull null
            val displayTitle =
                mostFrequentSpelling(members.mapNotNull { it.seriesTitle }) ?: identity

            // Honest counts: X = owned members actually completed; Y = every
            // distinct locally known Work of this cycle — the union of the
            // catalogue's Works sharing the identity and the members' own
            // Work rows (an import without a Works row is still known).
            val listenedCount = built.count { it.completed }
            val knownWorkIds = works.asSequence()
                .filter { normalizeOrNull(it.seriesTitle) == identity }
                .map { it.id }
                .toSet() +
                members.mapNotNull { it.workId }.toSet()

            // Representative cover: the unfinished member with the freshest
            // activity, else the first member in stable order.
            val representative = built.filter { !it.completed }
                .maxByOrNull { it.activityAt } ?: built.first()
            val finished = built.all { it.completed }

            Ranked(
                cycle = PersonalCycle(
                    title = displayTitle,
                    url = canonicalUrl,
                    coverImageUrl = representative.book.coverImageUrl,
                    listenedCount = listenedCount,
                    totalCount = knownWorkIds.size,
                    finished = finished
                ),
                finished = finished,
                activityAt = built.maxOf { it.activityAt }
            )
        }

        // --- T2: the similar tier (best-effort by construction) -------------

        // Every Work indexed by merge key (the candidate id) and by identity:
        // the lift resolves the recommended card's Work, then reuses the SAME
        // identity machinery as the own tier. First Work wins a merge-key
        // collision — the earliest spelling doctrine, deterministic.
        val worksByMergeKey = HashMap<String, WorkEntity>()
        for (work in works) {
            if (work.mergeKey.isNotBlank() && work.mergeKey !in worksByMergeKey) {
                worksByMergeKey[work.mergeKey] = work
            }
        }
        val worksByIdentity = works
            .filter { !it.seriesTitle.isNullOrBlank() }
            .groupBy { CollectionMatcher.normalizeTitle(it.seriesTitle!!) }

        // Owned identities are excluded from suggestions even when the own
        // cycle itself is omitted from the shelf (no openable URL): proposing
        // a named cycle the listener already owns is noise either way.
        val ownedIdentities = ownGroups.keys.toMutableSet()
        val similarSeen = mutableSetOf<String>()
        val similarCycles = mutableListOf<PersonalCycle>()

        // The recommendations arrive score-ranked from the engine (spec-19
        // sorts by similarity descending), so first occurrence = strongest
        // pick; dedup keeps exactly that reason.
        for (rec in recommendations) {
            val work = worksByMergeKey[rec.candidate.id] ?: continue
            val identity = normalizeOrNull(work.seriesTitle) ?: continue
            if (identity in ownedIdentities || identity in similarSeen) continue

            val group = worksByIdentity[identity].orEmpty()
            val fourReadUrls = fourReadUrlsOf(group.map { it.seriesUrl })
            if (fourReadUrls.isEmpty()) continue

            similarSeen += identity
            similarCycles += PersonalCycle(
                title = mostFrequentSpelling(group.mapNotNull { it.seriesTitle }) ?: identity,
                url = mostFrequentSpelling(fourReadUrls)!!,
                coverImageUrl = group.firstOrNull { !it.coverImageUrl.isNullOrBlank() }?.coverImageUrl,
                // The listener owns nothing here — the chip replaces progress.
                listenedCount = 0,
                totalCount = group.size,
                finished = false,
                reasonTitle = rec.reasonTitle
            )
        }

        return ownCycles
            .sortedWith(
                compareBy<Ranked> { it.finished }.thenByDescending { it.activityAt }
            )
            .map { it.cycle }
            .plus(similarCycles)
            .take(SHELF_LIMIT)
    }

    /** The subset of series URLs the series-page path can actually open. */
    private fun fourReadUrlsOf(seriesUrls: List<String?>): List<String> =
        seriesUrls.mapNotNull { url ->
            url?.takeIf { sourceIdForUrl(it) == SourceIds.FOUR_READ }
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
