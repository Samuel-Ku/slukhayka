package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.ChapterEntity
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
