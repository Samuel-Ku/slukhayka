package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity

/**
 * Wayfinder #62 — the rule-based personalized Listen screen.
 *
 * A transparent rules engine, NOT an opaque recommendation model: the
 * composer is a pure function `(library, next-in-series, prefs) → ordered
 * block list`, where every block carries its eligibility (already applied)
 * and a one-line **reason** the UI shows on the block header — the block
 * answers «чому це тут?» by itself. Nothing is fabricated and nothing is
 * network-dependent: a cold-start library renders only the source feeds and
 * the first-run CTAs, and blocks appear as their eligibility becomes true.
 *
 * Priority below is the DEFAULT order; a user's reorder (persisted in
 * [ListenPrefs.order]) wins. Hidden blocks stay computed but unrendered;
 * dismissed works are filtered out of every block.
 */
object ListenComposer {

    /**
     * The stable id of every Listen block — persisted in prefs, never renamed.
     *
     * [FINISHED] is appended rather than inserted: prefs store ids by name and
     * the order list is a plain `List<BlockId>`, so a new id that an existing
     * preference file does not mention is simply appended by [effectiveOrder]
     * instead of displacing anything the user already arranged.
     */
    enum class BlockId {
        HERO,
        ALMOST_DONE,
        RETURN,
        NEXT_IN_SERIES,
        TRAVEL,
        SHORT,
        FAVORITE_AUTHORS,
        RECENTLY_ADDED,
        FINISHED
    }

    /**
     * Every USER-FACING word the composer puts on a block. The pure module owns
     * eligibility and order, never a language: the app passes a resource-backed
     * implementation ([com.slukhayka.audiobooks.ui.library.ResourceListenCopy]),
     * tests pass a fixture one. #1478 — before this seam the module baked
     * Ukrainian copy into code, so a Polish phone showed Polish chrome around
     * Ukrainian shelf titles.
     */
    interface ListenCopy {
        fun title(id: BlockId): String
        fun almostDoneReason(remainingSeconds: Long): String
        fun returnReason(days: Int): String
        fun nextVolumeReason(seriesTitle: String): String
        fun offlineReadyReason(): String
        fun shortReason(totalDurationSeconds: Long): String
        fun favoriteAuthorReason(): String
        fun addedThisWeekReason(): String
        fun finishedReason(count: Int): String

        /** The «Наступна частина» caption of the next-in-series shelf card. */
        fun nextSeriesPartCaption(book: LibraryBook): String
    }

    /** One rendered block: already eligible, with its books and a reason line. */
    data class Block(
        val id: BlockId,
        val title: String,
        val reason: String?,
        val books: List<LibraryBook>
    )

    /**
     * The default (product) priority — first eligible block renders first.
     *
     * [BlockId.FINISHED] sits last: it is an archive shelf («що я вже
     * прослухав»), not a prompt to act, so it must never push the live blocks
     * — continue, almost done, return — below the fold.
     */
    val DEFAULT_ORDER = listOf(
        BlockId.HERO,
        BlockId.ALMOST_DONE,
        BlockId.RETURN,
        BlockId.NEXT_IN_SERIES,
        BlockId.TRAVEL,
        BlockId.SHORT,
        BlockId.FAVORITE_AUTHORS,
        BlockId.RECENTLY_ADDED,
        BlockId.FINISHED
    )

    /** «Ви слухали N днів тому» — a book not touched for this long is dormant. */
    const val DORMANT_DAYS = 14L

    /** «~2 год прослуховування» — a short book fits a commute session. */
    const val SHORT_BOOK_MAX_HOURS = 3L

    /** «Додано цього тижня» — recently-added window. */
    const val RECENT_ADDED_DAYS = 7L

    /**
     * Composes the eligible, ordered, prefs-filtered block list.
     *
     * @param library        every library card (buildLibraryBooks output).
     * @param nextInSeries   the next volume of the hero's series (spec-9 T4),
     *                       or null when there is none.
     * @param prefs          local-only preferences (order / hidden / dismissed).
     * @param finishedWorkIds works with at least one finished readthrough
     *                       (#1469). Admission to the FINISHED block is
     *                       `progress.isCompleted || workId in this set`, so
     *                       the manual «Позначити прослуханим» toggle counts
     *                       through `isCompleted` and a completed pass counts
     *                       through the readthrough, and neither alone is
     *                       required.
     * @param now            wall clock, injectable for tests.
     */
    fun compose(
        library: List<LibraryBook>,
        nextInSeries: AudiobookEntity?,
        prefs: ListenPrefs,
        finishedWorkIds: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
        copy: ListenCopy
    ): List<Block> {
        val dismissed = prefs.dismissedBookIds
        val visible = library.filter { it.book.id !in dismissed }

        val byId = buildMap {
            hero(visible, copy)?.let { put(BlockId.HERO, it) }
            almostDone(visible, copy)?.let { put(BlockId.ALMOST_DONE, it) }
            returnBlock(visible, now, copy)?.let { put(BlockId.RETURN, it) }
            nextInSeries?.let { put(BlockId.NEXT_IN_SERIES, nextInSeries(it, copy)) }
            travel(visible, copy)?.let { put(BlockId.TRAVEL, it) }
            shortBooks(visible, copy)?.let { put(BlockId.SHORT, it) }
            favoriteAuthors(visible, copy)?.let { put(BlockId.FAVORITE_AUTHORS, it) }
            recentlyAdded(visible, now, copy)?.let { put(BlockId.RECENTLY_ADDED, it) }
            finished(visible, finishedWorkIds, copy)?.let { put(BlockId.FINISHED, it) }
        }

        val order = effectiveOrder(prefs)
        return order.mapNotNull { byId[it] }
    }

    /** The user's reorder wins; unknown ids fall back to the default position. */
    private fun effectiveOrder(prefs: ListenPrefs): List<BlockId> {
        val userOrder = prefs.order
        if (userOrder.isEmpty()) return DEFAULT_ORDER
        val known = userOrder.filter { it in DEFAULT_ORDER }
        val rest = DEFAULT_ORDER.filter { it !in known }
        return known + rest
    }

    /** Block 1 — the hero: the most recently listened, not-yet-completed book. */
    private fun hero(library: List<LibraryBook>, copy: ListenCopy): Block? {
        val book = library
            .filter { it.progress != null && !it.isCompleted && it.percent < 1f }
            .maxByOrNull { it.lastListenedAt }
            ?: return null
        return Block(BlockId.HERO, copy.title(BlockId.HERO), reason = null, books = listOf(book))
    }

    /** Block 2 — «Майже дочитали»: ≥ 80 % through, not completed. */
    private fun almostDone(library: List<LibraryBook>, copy: ListenCopy): Block? {
        val books = library
            .filter { it.progress != null && !it.isCompleted && it.percent >= 0.8f && it.percent < 1f }
            .sortedByDescending { it.percent }
        if (books.isEmpty()) return null
        return Block(
            BlockId.ALMOST_DONE,
            copy.title(BlockId.ALMOST_DONE),
            reason = copy.almostDoneReason(books.first().remainingSeconds),
            books = books
        )
    }

    /** Block 3 — «Поверніться»: touched progress, but dormant for 14+ days. */
    private fun returnBlock(library: List<LibraryBook>, now: Long, copy: ListenCopy): Block? {
        val books = library
            .filter {
                it.progress != null && !it.isCompleted &&
                    it.lastListenedAt > 0L && now - it.lastListenedAt > DORMANT_DAYS * 24 * 3600 * 1000L
            }
            .sortedByDescending { it.lastListenedAt }
        if (books.isEmpty()) return null
        val days = ((now - books.first().lastListenedAt) / (24 * 3600 * 1000L)).toInt()
        return Block(
            BlockId.RETURN,
            copy.title(BlockId.RETURN),
            reason = copy.returnReason(days),
            books = books
        )
    }

    /** Block 4 — «Далі по серії»: the next volume of the hero's cycle (#57). */
    private fun nextInSeries(next: AudiobookEntity, copy: ListenCopy): Block? {
        val book = buildLibraryBooks(
            books = listOf(next),
            progressList = emptyList(),
            chaptersByBook = emptyMap()
        ).first()
        return Block(
            BlockId.NEXT_IN_SERIES,
            copy.title(BlockId.NEXT_IN_SERIES),
            reason = next.seriesTitle?.takeIf { it.isNotBlank() }?.let { copy.nextVolumeReason(it) },
            books = listOf(book)
        )
    }

    /** Block 5 — «Готово до поїздки»: downloaded books play offline (#59). */
    private fun travel(library: List<LibraryBook>, copy: ListenCopy): Block? {
        val books = library.filter { it.book.isDownloaded }.sortedByDescending { it.lastListenedAt }
        if (books.isEmpty()) return null
        return Block(
            BlockId.TRAVEL,
            copy.title(BlockId.TRAVEL),
            reason = copy.offlineReadyReason(),
            books = books
        )
    }

    /** Block 6 — «Щось коротке»: total duration ≤ 3 h. */
    private fun shortBooks(library: List<LibraryBook>, copy: ListenCopy): Block? {
        val maxSec = SHORT_BOOK_MAX_HOURS * 3600L
        val books = library
            .filter { it.totalDurationSeconds in 1L..maxSec }
            .sortedBy { it.totalDurationSeconds }
        if (books.isEmpty()) return null
        return Block(
            BlockId.SHORT,
            copy.title(BlockId.SHORT),
            reason = copy.shortReason(books.first().totalDurationSeconds),
            books = books
        )
    }

    /** Block 7 — «Улюблені автори / диктори»: favourite books, most-listened first. */
    private fun favoriteAuthors(library: List<LibraryBook>, copy: ListenCopy): Block? {
        val books = library.filter { it.book.isFavorite }.sortedByDescending { it.lastListenedAt }
        if (books.isEmpty()) return null
        return Block(
            BlockId.FAVORITE_AUTHORS,
            copy.title(BlockId.FAVORITE_AUTHORS),
            reason = copy.favoriteAuthorReason(),
            books = books
        )
    }

    /** Block 8 — «Нещодавно додані»: created within the last week. */
    private fun recentlyAdded(library: List<LibraryBook>, now: Long, copy: ListenCopy): Block? {
        val books = library
            .filter { it.book.createdAt > 0L && now - it.book.createdAt <= RECENT_ADDED_DAYS * 24 * 3600 * 1000L }
            .sortedByDescending { it.book.createdAt }
        if (books.isEmpty()) return null
        return Block(
            BlockId.RECENTLY_ADDED,
            copy.title(BlockId.RECENTLY_ADDED),
            reason = copy.addedThisWeekReason(),
            books = books
        )
    }

    /**
     * Block 9 — «Прослухані» (#1469): the books the listener has finished.
     *
     * A book qualifies on EITHER sign of completion, because they are genuinely
     * independent records of the same fact:
     *
     * - `progress.isCompleted` — the listener reached the end, or toggled
     *   «Позначити прослуханим» (that toggle writes this same flag through
     *   `setCompleted`), so the manual mark needs no separate field;
     * - a finished readthrough — a pass with `finishedAt != null`. A readthrough
     *   can be finished while a *newer* pass of the same work is under way,
     *   which leaves `isCompleted` false; ignoring it would drop a book the
     *   listener demonstrably finished.
     *
     * Newest first: `lastListenedAt` orders books that reclaimed this shelf.
     * A readthrough-only book has no progress clock, so it sorts last rather
     * than being dropped.
     */
    private fun finished(
        library: List<LibraryBook>,
        finishedWorkIds: Set<String>,
        copy: ListenCopy
    ): Block? {
        val books = library
            .filter { it.isCompleted || it.book.id in finishedWorkIds }
            .sortedByDescending { it.lastListenedAt }
        if (books.isEmpty()) return null
        return Block(
            BlockId.FINISHED,
            copy.title(BlockId.FINISHED),
            reason = copy.finishedReason(books.size),
            books = books
        )
    }

}

/**
 * Local-only Listen preferences (wayfinder #62): the user's block order,
 * hidden blocks and dismissed works. A preference, not an identity fact —
 * deliberately NEVER synced through the #56 corrections store, and fully
 * reversible from settings.
 */
interface ListenPrefs {
    /** The user's block order; empty = default priority. */
    val order: List<ListenComposer.BlockId>

    /** Blocks the user hid; hidden blocks stay computed but unrendered. */
    val hiddenBlockIds: Set<ListenComposer.BlockId>

    /** Works the user marked «Не цікаво» — filtered from every block. */
    val dismissedBookIds: Set<String>
}
