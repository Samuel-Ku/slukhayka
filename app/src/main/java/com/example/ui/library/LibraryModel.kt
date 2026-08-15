package com.example.ui.library

import com.example.data.db.AudiobookEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.data.source.sourceDisplayName
import com.example.data.source.sourceIdForUrl
import java.util.Locale

/**
 * Wayfinder #39 — Медіатека filters, sorting and the unified book card.
 *
 * Pure, platform-free library logic: every rule here is a function of the
 * entities, so the JVM test suite can pin each filter, each sort and the
 * search matching without Robolectric. The screen owns the UI state (which
 * filter/sort is selected); [filterAndSortLibrary] does the work.
 */

/** The quick filters of the unified library (Усі · Слухаю · … · Обрані). */
enum class LibraryFilter(val label: String) {
    ALL("Усі"),
    LISTENING("Слухаю"),
    COMPLETED("Завершені"),
    DOWNLOADED("Завантажені"),
    LOCAL("Локальні"),
    // Spec-15 T6: the multi-source catalog means "online" is any source, not
    // just 4read — the chip now says what it means.
    ONLINE("Онлайн"),
    FAVORITE("Обрані")
}

/** The six library sort modes (нещодавно слухані … за тривалістю). */
enum class LibrarySort(val label: String) {
    RECENTLY_LISTENED("Нещодавно слухані"),
    RECENTLY_ADDED("Нещодавно додані"),
    TITLE("За назвою"),
    AUTHOR("За автором"),
    PROGRESS("За прогресом"),
    DURATION("За тривалістю")
}

/**
 * A book as the library card shows it: the entity plus its playback state and
 * the metrics derived from the chapter table. `cumulativePositionSeconds` is
 * the wall-clock position inside the book (chapters before the current one
 * plus the in-chapter offset), so percent and remaining time are honest.
 */
data class LibraryBook(
    val book: AudiobookEntity,
    val progress: PlaybackProgressEntity?,
    val cumulativePositionSeconds: Long,
    val totalDurationSeconds: Long
) {
    val percent: Float
        get() = if (totalDurationSeconds <= 0L) 0f
        else (cumulativePositionSeconds.toFloat() / totalDurationSeconds).coerceIn(0f, 1f)

    val remainingSeconds: Long
        get() = (totalDurationSeconds - cumulativePositionSeconds).coerceAtLeast(0L)

    val isListening: Boolean
        get() = progress != null && !isCompleted && percent < 1f

    // The entity's isCompleted flag is currently informational only — the app
    // never writes it (end-of-book detection is the player-redesign ticket,
    // #38). Completion is therefore derived from position: a book whose
    // cumulative position reached its total duration counts as finished.
    val isCompleted: Boolean
        get() = progress?.isCompleted == true ||
            (totalDurationSeconds > 0L && cumulativePositionSeconds >= totalDurationSeconds)

    val lastListenedAt: Long
        get() = progress?.lastListenedAt ?: 0L

    /** Local imports carry a blank [AudiobookEntity.sourceUrl]; 4read books carry a URL. */
    val isLocal: Boolean
        get() = book.sourceUrl.isBlank()

    /**
     * Spec-15 T6 — the small source badge of the card: «Локальна» for local
     * imports, else the source's display name (4read, Sluhay, Sound-Books, …)
     * from the URL — never the hardcoded «4read» for a multi-source library.
     */
    val sourceName: String
        get() = sourceDisplayName(sourceIdForUrl(book.sourceUrl))

    /** «Сага про Дріззта · Книга 2» — or just the series title, or null. */
    val seriesLabel: String?
        get() {
            val series = book.seriesTitle?.takeIf { it.isNotBlank() } ?: return null
            val index = book.seriesIndex
            return if (index != null && index > 0) "$series · Книга $index" else series
        }
}

/**
 * Combines the raw flows into library cards. Chapter durations drive both the
 * real total duration (falling back to the book's own stamp when a book has no
 * chapters) and the cumulative position.
 */
fun buildLibraryBooks(
    books: List<AudiobookEntity>,
    progressList: List<PlaybackProgressEntity>,
    chaptersByBook: Map<String, List<ChapterEntity>>
): List<LibraryBook> {
    // Spec-10 T2: positions are per source, so several rows can exist for one
    // book; the card shows the latest (last-listened) one.
    val progressById = progressList
        .groupBy { it.bookId }
        .mapValues { (_, rows) -> rows.maxByOrNull { it.lastListenedAt } }
    return books.map { book ->
        val chapters = chaptersByBook[book.id].orEmpty().sortedBy { it.chapterIndex }
        val progress = progressById[book.id]
        // Same source of truth as the player (see [effectiveChapterDurations]):
        // the site-provided book total is authoritative — unknown (unplayed)
        // chapter durations are spread over the remainder so the book card
        // never shows a shrunken "37:23" for a 16:41:11 book. Locally imported
        // books (no site total) fall back to the sum of known chapters.
        val chapterDurations = effectiveChapterDurations(
            chapters = chapters,
            currentChapterIndex = progress?.currentChapterIndex ?: 0,
            currentChapterDurationMs = 0L,
            bookTotalDurationSeconds = book.totalDurationSeconds
        )
        val totalDuration = book.totalDurationSeconds.takeIf { it > 0L }
            ?: chapterDurations.sum()
        val cumulative = if (progress == null) 0L else {
            val beforeChapter = chapterDurations
                .take(progress.currentChapterIndex.coerceAtLeast(0))
                .sum()
            beforeChapter + progress.currentPositionSeconds
        }
        LibraryBook(
            book = book,
            progress = progress,
            cumulativePositionSeconds = cumulative,
            totalDurationSeconds = totalDuration
        )
    }
}

/**
 * Applies the quick filter, then the sort, then the search query. The query
 * matches title, author, narrator and series (case-insensitive, trimmed).
 * Sorts are deterministic: every rule ends with a title tie-break.
 */
fun filterAndSortLibrary(
    items: List<LibraryBook>,
    filter: LibraryFilter,
    sort: LibrarySort,
    query: String
): List<LibraryBook> {
    val filtered = items.filter { book -> matchesFilter(book, filter) }
    val trimmed = query.trim()
    val searched = if (trimmed.isEmpty()) filtered else filtered.filter { matchesQuery(it, trimmed) }
    return searched.sortedWith(comparatorFor(sort))
}

private fun matchesFilter(book: LibraryBook, filter: LibraryFilter): Boolean = when (filter) {
    LibraryFilter.ALL -> true
    LibraryFilter.LISTENING -> book.isListening
    LibraryFilter.COMPLETED -> book.isCompleted
    LibraryFilter.DOWNLOADED -> book.book.isDownloaded
    LibraryFilter.LOCAL -> book.isLocal
    LibraryFilter.ONLINE -> !book.isLocal
    LibraryFilter.FAVORITE -> book.book.isFavorite
}

private fun matchesQuery(book: LibraryBook, query: String): Boolean {
    val q = query.lowercase(Locale.getDefault())
    fun hit(value: String?) = value?.lowercase(Locale.getDefault())?.contains(q) == true
    return hit(book.book.title) || hit(book.book.author) || hit(book.book.narrator) ||
        hit(book.book.seriesTitle)
}

private fun comparatorFor(sort: LibrarySort): Comparator<LibraryBook> {
    val byTitle = compareBy<LibraryBook> { it.book.title.lowercase(Locale.getDefault()) }
    return when (sort) {
        LibrarySort.RECENTLY_LISTENED -> compareByDescending<LibraryBook> { it.lastListenedAt }.then(byTitle)
        LibrarySort.RECENTLY_ADDED -> compareByDescending<LibraryBook> { it.book.createdAt }.then(byTitle)
        LibrarySort.TITLE -> byTitle
        LibrarySort.AUTHOR -> compareBy<LibraryBook> { it.book.author.isBlank() }
            .thenBy { it.book.author.lowercase(Locale.getDefault()) }
            .then(byTitle)
        LibrarySort.PROGRESS -> compareBy<LibraryBook> { it.percent }.then(byTitle)
        LibrarySort.DURATION -> compareByDescending<LibraryBook> { it.totalDurationSeconds }.then(byTitle)
    }
}

/**
 * «Залишилось 4 год 12 хв» — the remaining-time line on the book card.
 * Returns "—" when the duration is unknown.
 */
fun formatRemainingTime(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "—"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0L && minutes > 0L -> "$hours год $minutes хв"
        hours > 0L -> "$hours год"
        minutes > 0L -> "$minutes хв"
        else -> "1 хв"
    }
}

/**
 * ADR-0011 — the OTHER rendition cards of a Work: every library card that
 * shares the same Work merge key but is not [selfId]. A Work with several
 * narrations therefore has several cards; the book page shows them in the
 * «Інші начитки» block (sorted by narrator). Blank-key rows (local imports)
 * have no Work and never produce siblings.
 */
fun siblingNarrations(
    books: List<AudiobookEntity>,
    selfId: String,
    mergeKey: String
): List<AudiobookEntity> =
    books.filter { it.mergeKey.isNotBlank() && it.mergeKey == mergeKey && it.id != selfId }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.narrator })
