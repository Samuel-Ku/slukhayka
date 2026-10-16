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
 * get the raw known-sum behaviour back — for POSITIONS. The book's own TOTAL is
 * a different question, answered by [knownBookTotalSeconds]: a local book with
 * only some chapters measured has no total at all (#1187).
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
 * #1187 — the book's own total, as ADR-0014 defines knowledge: the
 * site-provided value when there is one, otherwise the sum of the chapters
 * **only when every one of them has been measured**. A partial sum is not a
 * shorter book — it is a count of what happened to be played or probed so far,
 * and it grows while the listener listens.
 *
 * This is the line the local import crossed. A local book has no site total, so
 * the card took the sum of the KNOWN chapters: three 45-second chapters with
 * one played made the whole book "45 seconds long", and a listener 60 s into
 * the third chapter read as `60 >= 45` → «Завершена», even though two thirds of
 * the book had never been heard. The same partial sum fed the player's
 * re-listen rule.
 *
 * The price of the refusal is paid in the open, and it is deliberate: a local
 * book whose files cannot be measured — a deleted folder, an unreadable
 * container, a codec [android.media.MediaMetadataRetriever] does not know —
 * has no total at all, so the card shows the honest unknown state
 * («Тривалість невідома», [com.slukhayka.audiobooks.R.string.a11y_library_progress_unknown])
 * and the book is never called finished by position. Nothing else may be
 * invented from file size or bitrate for a local file: those are streams'
 * arithmetic, and [com.slukhayka.audiobooks.data.duration.ChapterDurationProbe]
 * measures real local containers instead.
 */
fun knownBookTotalSeconds(
    chapters: List<ChapterEntity>,
    bookTotalDurationSeconds: Long
): Long {
    if (bookTotalDurationSeconds > 0L) return bookTotalDurationSeconds
    if (chapters.isEmpty()) return 0L
    if (chapters.any { it.durationSeconds <= 0L }) return 0L
    return chapters.sumOf { it.durationSeconds }
}

/**
 * #1187 — whether a LOAD starts at the end of the book, for the ONE completion
 * rule ([isBookFinished]): the chapters before [chapterIndex] plus the
 * in-chapter [positionSeconds] against [knownBookTotalSeconds].
 *
 * The player used to answer this by comparing the in-chapter position with the
 * sum of the loaded chapters — two different rulers. For a local book with one
 * played 45-second chapter, a listener 60 s into the third chapter "started at
 * the end" and was thrown back to chapter 0 while two thirds of the book were
 * still unheard. A load is a re-listen only when the book's own end is known
 * and the start actually sits there; an unknown total never triggers it.
 */
fun startsAtBookEnd(
    chapters: List<ChapterEntity>,
    chapterIndex: Int,
    positionSeconds: Long,
    bookTotalDurationSeconds: Long
): Boolean {
    if (chapters.isEmpty()) return false
    val index = chapterIndex.coerceIn(chapters.indices)
    val durations = effectiveChapterDurations(
        chapters = chapters,
        currentChapterIndex = index,
        currentChapterDurationMs = 0L,
        bookTotalDurationSeconds = bookTotalDurationSeconds
    )
    return isBookFinished(
        completedManually = false,
        cumulativePositionSeconds = durations.take(index).sum() + positionSeconds.coerceAtLeast(0L),
        totalDurationSeconds = knownBookTotalSeconds(chapters, bookTotalDurationSeconds)
    )
}

/**
 * The book-level position and total of one Listening State row: the same
 * computation the library card has always shown (unknown chapter durations are
 * spread over the authoritative book total, ADR-0014 keeps the real one
 * authoritative). `progress == null` is an untouched book: no position, and the
 * total still comes from the book row's own claim — or, for a local import
 * whose chapters were all measured, from their sum ([knownBookTotalSeconds]).
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
    val total = knownBookTotalSeconds(chapters, bookTotalDurationSeconds)
    val beforeChapter = chapterDurations.take(currentChapterIndex).sum()
    return BookProgress(
        cumulativePositionSeconds = if (progress == null) 0L else beforeChapter + progress.currentPositionSeconds,
        totalDurationSeconds = total,
        completedManually = progress?.isCompleted == true
    )
}
