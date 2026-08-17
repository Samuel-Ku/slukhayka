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

    /** The stable id of every Listen block — persisted in prefs, never renamed. */
    enum class BlockId { HERO, ALMOST_DONE, RETURN, NEXT_IN_SERIES, TRAVEL, SHORT, FAVORITE_AUTHORS, RECENTLY_ADDED }

    /** One rendered block: already eligible, with its books and a reason line. */
    data class Block(
        val id: BlockId,
        val title: String,
        val reason: String?,
        val books: List<LibraryBook>
    )

    /** The default (product) priority — first eligible block renders first. */
    val DEFAULT_ORDER = listOf(
        BlockId.HERO,
        BlockId.ALMOST_DONE,
        BlockId.RETURN,
        BlockId.NEXT_IN_SERIES,
        BlockId.TRAVEL,
        BlockId.SHORT,
        BlockId.FAVORITE_AUTHORS,
        BlockId.RECENTLY_ADDED
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
     * @param library       every library card (buildLibraryBooks output).
     * @param nextInSeries  the next volume of the hero's series (spec-9 T4),
     *                      or null when there is none.
     * @param prefs         local-only preferences (order / hidden / dismissed).
     * @param now           wall clock, injectable for tests.
     */
    fun compose(
        library: List<LibraryBook>,
        nextInSeries: AudiobookEntity?,
        prefs: ListenPrefs,
        now: Long = System.currentTimeMillis()
    ): List<Block> {
        val dismissed = prefs.dismissedBookIds
        val visible = library.filter { it.book.id !in dismissed }

        val byId = buildMap {
            hero(visible)?.let { put(BlockId.HERO, it) }
            almostDone(visible)?.let { put(BlockId.ALMOST_DONE, it) }
            returnBlock(visible, now)?.let { put(BlockId.RETURN, it) }
            nextInSeries?.let { put(BlockId.NEXT_IN_SERIES, nextInSeries(it)) }
            travel(visible)?.let { put(BlockId.TRAVEL, it) }
            shortBooks(visible)?.let { put(BlockId.SHORT, it) }
            favoriteAuthors(visible)?.let { put(BlockId.FAVORITE_AUTHORS, it) }
            recentlyAdded(visible, now)?.let { put(BlockId.RECENTLY_ADDED, it) }
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
    private fun hero(library: List<LibraryBook>): Block? {
        val book = library
            .filter { it.progress != null && !it.isCompleted && it.percent < 1f }
            .maxByOrNull { it.lastListenedAt }
            ?: return null
        return Block(BlockId.HERO, "Продовжити слухати", reason = null, books = listOf(book))
    }

    /** Block 2 — «Майже дочитали»: ≥ 80 % through, not completed. */
    private fun almostDone(library: List<LibraryBook>): Block? {
        val books = library
            .filter { it.progress != null && !it.isCompleted && it.percent >= 0.8f && it.percent < 1f }
            .sortedByDescending { it.percent }
        if (books.isEmpty()) return null
        val reason = books.first().let { "До кінця ${formatRemainingTime(it.remainingSeconds)}" }
        return Block(BlockId.ALMOST_DONE, "Майже дочитали", reason = reason, books = books)
    }

    /** Block 3 — «Поверніться»: touched progress, but dormant for 14+ days. */
    private fun returnBlock(library: List<LibraryBook>, now: Long): Block? {
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
            "Поверніться",
            reason = "Ви слухали $days ${pluralDays(days)} тому",
            books = books
        )
    }

    /** Block 4 — «Далі по серії»: the next volume of the hero's cycle (#57). */
    private fun nextInSeries(next: AudiobookEntity): Block? {
        val book = buildLibraryBooks(
            books = listOf(next),
            progressList = emptyList(),
            chaptersByBook = emptyMap()
        ).first()
        return Block(
            BlockId.NEXT_IN_SERIES,
            "Продовжити серію",
            reason = next.seriesTitle?.takeIf { it.isNotBlank() }?.let { "Наступний том: $it" },
            books = listOf(book)
        )
    }

    /** Block 5 — «Готово до поїздки»: downloaded books play offline (#59). */
    private fun travel(library: List<LibraryBook>): Block? {
        val books = library.filter { it.book.isDownloaded }.sortedByDescending { it.lastListenedAt }
        if (books.isEmpty()) return null
        return Block(BlockId.TRAVEL, "Готово до поїздки", reason = "Завантажено · працює офлайн", books = books)
    }

    /** Block 6 — «Щось коротке»: total duration ≤ 3 h. */
    private fun shortBooks(library: List<LibraryBook>): Block? {
        val maxSec = SHORT_BOOK_MAX_HOURS * 3600L
        val books = library
            .filter { it.totalDurationSeconds in 1L..maxSec }
            .sortedBy { it.totalDurationSeconds }
        if (books.isEmpty()) return null
        val hours = books.first().totalDurationSeconds / 3600.0
        val label = if (hours < 1.0) "менше години" else "~${kotlin.math.round(hours)} год"
        return Block(BlockId.SHORT, "Щось коротке", reason = "$label прослуховування", books = books)
    }

    /** Block 7 — «Улюблені автори / диктори»: favourite books, most-listened first. */
    private fun favoriteAuthors(library: List<LibraryBook>): Block? {
        val books = library.filter { it.book.isFavorite }.sortedByDescending { it.lastListenedAt }
        if (books.isEmpty()) return null
        return Block(
            BlockId.FAVORITE_AUTHORS,
            "Улюблені автори",
            reason = "Ви часто слухаєте цього автора",
            books = books
        )
    }

    /** Block 8 — «Нещодавно додані»: created within the last week. */
    private fun recentlyAdded(library: List<LibraryBook>, now: Long): Block? {
        val books = library
            .filter { it.book.createdAt > 0L && now - it.book.createdAt <= RECENT_ADDED_DAYS * 24 * 3600 * 1000L }
            .sortedByDescending { it.book.createdAt }
        if (books.isEmpty()) return null
        return Block(BlockId.RECENTLY_ADDED, "Нещодавно додані", reason = "Додано цього тижня", books = books)
    }

    private fun pluralDays(days: Int): String = when {
        days % 10 == 1 && days % 100 != 11 -> "день"
        days % 10 in 2..4 && days % 100 !in 12..14 -> "дні"
        else -> "днів"
    }
}

/**
 * spec-28 (#201) — the «Наступна частина» context caption for the
 * «Далі у серії» shelf card: WHICH part of the series is next. The volume
 * number when the series numbers its books, else the plain context line.
 * The series NAME rides in the block header reason («Наступний том: …»).
 * The one-tap play triangle is NOT restored (ADR-0018 forbids it on the
 * shelf card) — only the context returns.
 */
fun nextSeriesPartCaption(book: LibraryBook): String {
    val index = book.book.seriesIndex
    return if (index != null && index > 0) "Частина $index" else "Наступна частина"
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
