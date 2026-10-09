package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import kotlin.math.max

/**
 * Chapter durations on the shared book timeline (player + library).
 *
 * Only chapters that have actually been PLAYED carry a real durationSeconds in
 * Room (persistRealDurationIfKnown writes it on READY); untouched chapters
 * store 0. Naively summing those zeroes under-reports the book — on-device a
 * 16:41:11 book showed "37:23" on the player because just 5 of 65 chapters had
 * known durations. The site-provided [bookTotalDurationSeconds] is
 * authoritative: unknown chapters are spread evenly over the remainder (with
 * the rounding remainder distributed one second at a time) so the total always
 * lands exactly on the real book length, and positions/fractions/seeks stay
 * honest.
 *
 * Callers without an authoritative total (locally imported books) pass 0 and
 * get the raw known-sum behaviour back.
 */
fun effectiveChapterDurations(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    currentChapterDurationMs: Long,
    bookTotalDurationSeconds: Long = 0L
): List<Long> {
    if (chapters.isEmpty()) return emptyList()
    val selectedIndex = currentChapterIndex.coerceIn(chapters.indices)
    val durations = chapters.map { it.durationSeconds.coerceAtLeast(0L) }.toMutableList()
    // The playing chapter's measured duration overrides the row (it is exact).
    durations[selectedIndex] = max(durations[selectedIndex], (currentChapterDurationMs / 1_000L).coerceAtLeast(0L))

    if (bookTotalDurationSeconds <= 0L) return durations

    val knownSum = durations.sum()
    if (knownSum >= bookTotalDurationSeconds) return durations

    val unknownIndices = durations.indices.filter { durations[it] <= 0L }
    if (unknownIndices.isEmpty()) return durations

    val missing = bookTotalDurationSeconds - knownSum
    val perUnknown = missing / unknownIndices.size
    val extra = (missing % unknownIndices.size).toInt()
    // Spread: every unknown chapter gets `perUnknown` seconds, and the first
    // `extra` ones get one extra second, so the sum is exactly bookTotal even
    // when perUnknown rounds to 0 (tiny-margin edge).
    unknownIndices.forEachIndexed { i, idx ->
        durations[idx] = perUnknown + if (i < extra) 1L else 0L
    }
    return durations
}

/**
 * #1174 — where the listener is in ONE book, and whether that means finished.
 *
 * This lived in `ui/library` while the abandon mark arrived, and the review
 * caught the consequence: the mark's door is a DATA module and had grown its
 * own, weaker idea of "finished" (`progress.isCompleted` alone), so a book
 * playing its last seconds could be marked while the library already called it
 * finished — and the mark then sat in the database with no surface able to
 * cancel it. The rule now lives where both layers can reach it: one
 * computation ([bookProgress]) and one verdict ([isBookFinished]).
 */
data class BookProgress(
    /** Wall-clock position inside the book: the chapters before the current
     *  one plus the offset in it. */
    val cumulativePositionSeconds: Long,
    /** The book's known total; 0 when nothing proves one (ADR-0014). */
    val totalDurationSeconds: Long,
    /** The Listening State row's own «Прослухано» flag — a real listener act,
     *  never inferred from a position. */
    val completedManually: Boolean
) {
    val isFinished: Boolean
        get() = isBookFinished(completedManually, cumulativePositionSeconds, totalDurationSeconds)
}

/**
 * The app's ONE completion rule: the manual «Прослухано» flag, or a position
 * that reached the book's known end. A book without a known total is never
 * "finished" by position — an unknown number proves nothing (ADR-0014).
 */
fun isBookFinished(
    completedManually: Boolean,
    cumulativePositionSeconds: Long,
    totalDurationSeconds: Long
): Boolean = completedManually ||
    (totalDurationSeconds > 0L && cumulativePositionSeconds >= totalDurationSeconds)

/**
 * The book-level position and total of one Listening State row: the same
 * computation the library card has always shown (unknown chapter durations are
 * spread over the authoritative book total, ADR-0014 keeps the real one
 * authoritative). `progress == null` is an untouched book: no position, and the
 * total still comes from the book row's own claim.
 */
fun bookProgress(
    chapters: List<ChapterEntity>,
    progress: PlaybackProgressEntity?,
    bookTotalDurationSeconds: Long
): BookProgress {
    val currentChapterIndex = (progress?.currentChapterIndex ?: 0).coerceAtLeast(0)
    val chapterDurations = effectiveChapterDurations(
        chapters = chapters,
        currentChapterIndex = currentChapterIndex,
        currentChapterDurationMs = 0L,
        bookTotalDurationSeconds = bookTotalDurationSeconds
    )
    val total = bookTotalDurationSeconds.takeIf { it > 0L } ?: chapterDurations.sum()
    val beforeChapter = chapterDurations.take(currentChapterIndex).sum()
    return BookProgress(
        cumulativePositionSeconds = if (progress == null) 0L else beforeChapter + progress.currentPositionSeconds,
        totalDurationSeconds = total,
        completedManually = progress?.isCompleted == true
    )
}
