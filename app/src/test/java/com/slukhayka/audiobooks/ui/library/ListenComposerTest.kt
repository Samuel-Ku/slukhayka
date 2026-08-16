package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the wayfinder #62 ListenComposer (no Robolectric). */
class ListenComposerTest {

    // --- fixtures ----------------------------------------------------------

    private fun book(
        id: String,
        title: String,
        author: String = "Автор",
        totalDurationSeconds: Long = 3600L,
        isDownloaded: Boolean = false,
        isFavorite: Boolean = false,
        seriesTitle: String? = null,
        createdAt: Long = 0L
    ) = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = "Читець",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "https://4read.org/$id.html",
        isDownloaded = isDownloaded,
        totalDurationSeconds = totalDurationSeconds
    ).also {
        // ADR-0009: @Ignore projections set in place.
        it.isFavorite = isFavorite
        it.seriesTitle = seriesTitle
        it.createdAt = createdAt
    }

    private fun progress(
        bookId: String,
        position: Long,
        lastListenedAt: Long = 0L
    ) = PlaybackProgressEntity(
        // ADR-0007: progress is Edition-keyed — one row per rendition.
        editionId = "ed-$bookId",
        bookId = bookId,
        currentChapterIndex = 0,
        currentPositionSeconds = position,
        lastListenedAt = lastListenedAt,
        isCompleted = false
    )

    private fun card(
        book: AudiobookEntity,
        progress: PlaybackProgressEntity? = null,
        totalDuration: Long = book.totalDurationSeconds
    ) = LibraryBook(
        book = book,
        progress = progress,
        cumulativePositionSeconds = progress?.currentPositionSeconds ?: 0L,
        totalDurationSeconds = totalDuration
    )

    private fun prefs(
        order: List<ListenComposer.BlockId> = emptyList(),
        hidden: Set<ListenComposer.BlockId> = emptySet(),
        dismissed: Set<String> = emptySet()
    ) = object : ListenPrefs {
        override val order = order
        override val hiddenBlockIds = hidden
        override val dismissedBookIds = dismissed
    }

    private val NOW = 1_700_000_000_000L
    private val DAY = 24 * 3600 * 1000L

    // --- block eligibility and priority -------------------------------------

    @Test
    fun `empty library composes to no blocks`() {
        val blocks = ListenComposer.compose(emptyList(), nextInSeries = null, prefs = prefs(), now = NOW)
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `hero is the most recently listened unfinished book`() {
        val b1 = card(book("b1", "Перша"), progress("b1", position = 100L, lastListenedAt = NOW - DAY))
        val b2 = card(book("b2", "Друга"), progress("b2", position = 50L, lastListenedAt = NOW))

        val blocks = ListenComposer.compose(listOf(b1, b2), nextInSeries = null, prefs = prefs(), now = NOW)

        assertEquals(ListenComposer.BlockId.HERO, blocks.first().id)
        assertEquals("b2", blocks.first().books.single().book.id)
        assertNull(blocks.first().reason)
    }

    @Test
    fun `completed hero is excluded and the next best becomes hero`() {
        val done = card(book("done", "Завершена"), progress("done", position = 3600L))
        val half = card(book("half", "Половина"), progress("half", position = 100L, lastListenedAt = NOW))

        val blocks = ListenComposer.compose(listOf(done, half), nextInSeries = null, prefs = prefs(), now = NOW)

        val hero = blocks.first { it.id == ListenComposer.BlockId.HERO }
        assertEquals("half", hero.books.single().book.id)
    }

    @Test
    fun `almost-done block lists books at 80 percent with remaining-time reason`() {
        val near = card(book("near", "Майже"), progress("near", position = 3200L)) // 3200/3600 = 89 %
        val far = card(book("far", "Далеко"), progress("far", position = 100L))

        val blocks = ListenComposer.compose(listOf(near, far), nextInSeries = null, prefs = prefs(), now = NOW)

        val block = blocks.first { it.id == ListenComposer.BlockId.ALMOST_DONE }
        assertEquals(listOf("near"), block.books.map { it.book.id })
        assertTrue(block.reason!!.startsWith("До кінця"))
    }

    @Test
    fun `return block lists dormant unfinished books with days-ago reason`() {
        val dormant = card(book("old", "Старе"), progress("old", position = 100L, lastListenedAt = NOW - 20 * DAY))
        val recent = card(book("new", "Свіже"), progress("new", position = 100L, lastListenedAt = NOW - DAY))

        val blocks = ListenComposer.compose(listOf(dormant, recent), nextInSeries = null, prefs = prefs(), now = NOW)

        val block = blocks.first { it.id == ListenComposer.BlockId.RETURN }
        assertEquals(listOf("old"), block.books.map { it.book.id })
        assertTrue(block.reason!!.contains("20"))
    }

    @Test
    fun `next-in-series block renders the passed next volume`() {
        val next = book("next", "Наступна", seriesTitle = "Сага")
        val blocks = ListenComposer.compose(
            emptyList(),
            nextInSeries = next,
            prefs = prefs(),
            now = NOW
        )

        val block = blocks.first { it.id == ListenComposer.BlockId.NEXT_IN_SERIES }
        assertEquals("next", block.books.single().book.id)
        assertTrue(block.reason!!.contains("Сага"))
    }

    @Test
    fun `travel block lists downloaded books with offline reason`() {
        val offline = card(book("off", "Офлайн", isDownloaded = true))
        val online = card(book("on", "Онлайн"))

        val blocks = ListenComposer.compose(listOf(offline, online), nextInSeries = null, prefs = prefs(), now = NOW)

        val block = blocks.first { it.id == ListenComposer.BlockId.TRAVEL }
        assertEquals(listOf("off"), block.books.map { it.book.id })
        assertTrue(block.reason!!.contains("офлайн"))
    }

    @Test
    fun `short block lists books up to three hours with duration reason`() {
        val short = card(book("s", "Коротка", totalDurationSeconds = 2 * 3600L))
        val long = card(book("l", "Довга", totalDurationSeconds = 10 * 3600L))

        val blocks = ListenComposer.compose(listOf(short, long), nextInSeries = null, prefs = prefs(), now = NOW)

        val block = blocks.first { it.id == ListenComposer.BlockId.SHORT }
        assertEquals(listOf("s"), block.books.map { it.book.id })
        assertTrue(block.reason!!.contains("год"))
    }

    @Test
    fun `favorites block lists favourite books`() {
        val fav = card(book("f", "Улюблена", isFavorite = true))
        val plain = card(book("p", "Звичайна"))

        val blocks = ListenComposer.compose(listOf(fav, plain), nextInSeries = null, prefs = prefs(), now = NOW)

        val block = blocks.first { it.id == ListenComposer.BlockId.FAVORITE_AUTHORS }
        assertEquals(listOf("f"), block.books.map { it.book.id })
    }

    @Test
    fun `recently-added block lists books created this week`() {
        val fresh = card(book("fr", "Нове", createdAt = NOW - DAY))
        val ancient = card(book("an", "Старе", createdAt = NOW - 30 * DAY))

        val blocks = ListenComposer.compose(listOf(fresh, ancient), nextInSeries = null, prefs = prefs(), now = NOW)

        val block = blocks.first { it.id == ListenComposer.BlockId.RECENTLY_ADDED }
        assertEquals(listOf("fr"), block.books.map { it.book.id })
        assertTrue(block.reason!!.contains("тижня"))
    }

    // --- default priority ---------------------------------------------------

    @Test
    fun `blocks follow the default product priority`() {
        val all = listOf(
            card(book("a", "Книга", totalDurationSeconds = 10 * 3600L), progress("a", position = 100L, lastListenedAt = NOW)),
            card(book("b", "Майже", totalDurationSeconds = 2 * 3600L), progress("b", position = 6000L, lastListenedAt = NOW - DAY)),
            card(book("c", "Офлайн", isDownloaded = true, totalDurationSeconds = 10 * 3600L)),
            card(book("d", "Улюблена", isFavorite = true, totalDurationSeconds = 10 * 3600L)),
            card(book("e", "Нова", createdAt = NOW, totalDurationSeconds = 10 * 3600L))
        )

        val blocks = ListenComposer.compose(all, nextInSeries = book("n", "Наступна"), prefs = prefs(), now = NOW)

        assertEquals(
            listOf(
                ListenComposer.BlockId.HERO,
                ListenComposer.BlockId.ALMOST_DONE,
                ListenComposer.BlockId.NEXT_IN_SERIES,
                ListenComposer.BlockId.TRAVEL,
                ListenComposer.BlockId.SHORT,
                ListenComposer.BlockId.FAVORITE_AUTHORS,
                ListenComposer.BlockId.RECENTLY_ADDED
            ),
            blocks.map { it.id }
        )
    }

    // --- prefs: order / hidden / dismissed ----------------------------------

    @Test
    fun `user order wins over the default priority`() {
        val all = listOf(
            card(book("a", "Книга", totalDurationSeconds = 10 * 3600L), progress("a", position = 100L, lastListenedAt = NOW)),
            card(book("c", "Офлайн", isDownloaded = true, totalDurationSeconds = 10 * 3600L))
        )

        val blocks = ListenComposer.compose(
            all,
            nextInSeries = null,
            prefs = prefs(order = listOf(ListenComposer.BlockId.TRAVEL, ListenComposer.BlockId.HERO)),
            now = NOW
        )

        assertEquals(
            listOf(ListenComposer.BlockId.TRAVEL, ListenComposer.BlockId.HERO),
            blocks.map { it.id }
        )
    }

    @Test
    fun `hidden blocks are still computed by the composer and filtered by the screen`() {
        val all = listOf(
            card(book("a", "Книга", totalDurationSeconds = 10 * 3600L), progress("a", position = 100L, lastListenedAt = NOW)),
            card(book("c", "Офлайн", isDownloaded = true, totalDurationSeconds = 10 * 3600L))
        )

        val blocks = ListenComposer.compose(
            all,
            nextInSeries = null,
            prefs = prefs(hidden = setOf(ListenComposer.BlockId.TRAVEL)),
            now = NOW
        )

        // The composer itself does not filter — hidden stays computed; the UI
        // decides what to render. This keeps re-showing instant, no state loss.
        assertEquals(2, blocks.size)
        assertTrue(blocks.any { it.id == ListenComposer.BlockId.TRAVEL })
    }

    @Test
    fun `dismissed works are filtered from every block`() {
        val near = card(book("near", "Майже"), progress("near", position = 3400L, lastListenedAt = NOW))
        val other = card(book("other", "Інша"), progress("other", position = 3400L, lastListenedAt = NOW - DAY))

        val blocks = ListenComposer.compose(
            listOf(near, other),
            nextInSeries = null,
            prefs = prefs(dismissed = setOf("near")),
            now = NOW
        )

        val block = blocks.first { it.id == ListenComposer.BlockId.ALMOST_DONE }
        assertEquals(listOf("other"), block.books.map { it.book.id })
    }

    @Test
    fun `an unknown order id falls back to the default position`() {
        val all = listOf(
            card(book("a", "Книга", totalDurationSeconds = 10 * 3600L), progress("a", position = 100L, lastListenedAt = NOW))
        )

        val blocks = ListenComposer.compose(
            all,
            nextInSeries = null,
            prefs = prefs(order = listOf(ListenComposer.BlockId.RETURN, ListenComposer.BlockId.HERO)),
            now = NOW
        )

        assertEquals(listOf(ListenComposer.BlockId.HERO), blocks.map { it.id })
    }
}
