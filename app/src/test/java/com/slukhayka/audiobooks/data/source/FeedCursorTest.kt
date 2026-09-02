package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.privacy.PacingParams
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Spec #462 ID4 (#466) — the sluhayua feed cursor: one user-initiated pull
 * takes successive pages until the configured limit or an empty page, and
 * the pauses between pages keep the human rhythm (spec-38). Deterministic
 * by injection — a recording fetch lambda and a no-op pause, no sleeping.
 */
class FeedCursorTest {

    private class RecordingCursor(
        pages: Map<Int, List<SourceBook>>,
        maxBooks: Int = FeedCursor.DEFAULT_MAX_BOOKS,
        maxPages: Int = FeedCursor.DEFAULT_MAX_PAGES,
        pacing: PacingPolicy = PacingPolicy()
    ) {
        val requestedPages = mutableListOf<Int>()
        val pauses = mutableListOf<Long>()
        val cursor = FeedCursor(
            fetchPage = { page ->
                requestedPages += page
                pages[page].orEmpty()
            },
            maxBooks = maxBooks,
            maxPages = maxPages,
            pacing = pacing,
            pauseMillis = { pauses += it }
        )
    }

    // A fixed 100 ms pause: a seeded policy draws an exact sequence without
    // sleeping (the injected pauseMillis only records).
    private val fixedPacing = PacingPolicy(
        PacingParams(minPauseMillis = 100, maxPauseMillis = 100),
        Random(42)
    )

    private fun book(n: Int) = SourceBook(title = "Книга $n", author = "Автор", url = "u$n", sourceId = "sluhayua")

    @Test
    fun `pull takes successive pages until the configured book limit`() = runBlocking {
        val recording = RecordingCursor(
            pages = (1..10).associateWith { page -> listOf(book(page * 3), book(page * 3 + 1), book(page * 3 + 2)) },
            maxBooks = 7
        )

        val books = recording.cursor.fetchNext()

        assertEquals(7, books.size)
        assertEquals(listOf(1, 2, 3), recording.requestedPages)
        assertEquals(3, recording.cursor.fetchedPages)
        assertEquals(4, recording.cursor.nextPage)
        // The limit stop is NOT the feed's end — more pages may exist.
        assertFalse(recording.cursor.isExhausted)
    }

    @Test
    fun `pull stops on an empty page and the feed is exhausted`() = runBlocking {
        val recording = RecordingCursor(
            pages = mapOf(1 to listOf(book(1), book(2)), 2 to emptyList()),
            maxPages = 10
        )

        val books = recording.cursor.fetchNext()

        assertEquals(listOf(book(1), book(2)), books)
        assertEquals(listOf(1, 2), recording.requestedPages)
        assertTrue(recording.cursor.isExhausted)
        // After the honest end, further pulls ask for nothing.
        assertTrue(recording.cursor.fetchNext().isEmpty())
        assertEquals(listOf(1, 2), recording.requestedPages)
    }

    @Test
    fun `pull stops at the max pages safety bound`() = runBlocking {
        val recording = RecordingCursor(
            pages = (1..10).associateWith { page -> listOf(book(page)) },
            maxBooks = 100,
            maxPages = 2
        )

        val books = recording.cursor.fetchNext()

        assertEquals(2, books.size)
        assertEquals(listOf(1, 2), recording.requestedPages)
        assertFalse(recording.cursor.isExhausted)
        // The safety bound holds on the next pull too — never a crawl.
        assertTrue(recording.cursor.fetchNext().isEmpty())
        assertEquals(listOf(1, 2), recording.requestedPages)
    }

    @Test
    fun `pages are paced between, never before the first`() = runBlocking {
        val recording = RecordingCursor(
            pages = (1..5).associateWith { page -> listOf(book(page)) },
            maxBooks = 5,
            pacing = fixedPacing
        )

        val books = recording.cursor.fetchNext()

        assertEquals(5, books.size)
        // One pause BETWEEN consecutive page requests only: 5 pages → 4 pauses.
        assertEquals(listOf(100L, 100L, 100L, 100L), recording.pauses)
    }
}
