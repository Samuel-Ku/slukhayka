package com.example.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Self-test for the fixture factory (GitHub issue #6).
 *
 * These are invariant assertions, not example assertions: every downstream test
 * (`AudioPlayerManagerTest`, repository tests, snapshot tests) relies on the
 * shape guaranteed here, so a change to [TestDataFactory] that breaks the
 * contract fails once, loudly, in this file.
 */
class TestDataFactoryTest {

    @Test
    fun `dataBooks produces three books with unique stable ids`() {
        // Arrange / Act
        val books = TestDataFactory.dataBooks()

        // Assert
        assertEquals(TestDataFactory.BOOK_COUNT, books.size)
        assertEquals(books.size, books.map { it.id }.distinct().size)
        assertTrue("ids must be non-blank", books.all { it.id.isNotBlank() })
    }

    @Test
    fun `dataBooks is deterministic across invocations`() {
        // Arrange / Act
        val first = TestDataFactory.dataBooks()
        val second = TestDataFactory.dataBooks()

        // Assert
        assertEquals(first, second)
        assertEquals(TestDataFactory.dataChapters(), TestDataFactory.dataChapters())
        assertEquals(TestDataFactory.seedSources(), TestDataFactory.seedSources())
    }

    @Test
    fun `dataChapters produces nine chapters, three per book`() {
        // Arrange
        val books = TestDataFactory.dataBooks()

        // Act
        val chapters = TestDataFactory.dataChapters(books)

        // Assert
        assertEquals(TestDataFactory.TOTAL_CHAPTERS, chapters.size)
        assertEquals(9, chapters.size)
        books.forEach { book ->
            val ownChapters = chapters.filter { it.bookId == book.id }
            assertEquals(TestDataFactory.CHAPTERS_PER_BOOK, ownChapters.size)
            assertEquals(listOf(0, 1, 2), ownChapters.map { it.chapterIndex })
        }
        assertEquals(chapters.size, chapters.map { it.id }.distinct().size)
    }

    @Test
    fun `book metadata agrees with its generated chapters`() {
        // Arrange
        val books = TestDataFactory.dataBooks()

        // Act / Assert
        books.forEach { book ->
            val ownChapters = TestDataFactory.chaptersFor(book)
            assertEquals(book.totalChapters, ownChapters.size)
            assertEquals(book.totalDurationSeconds, ownChapters.sumOf { it.durationSeconds })
        }
    }

    @Test
    fun `chapter durations differ so off-by-one errors are visible`() {
        // Arrange / Act
        val durations = TestDataFactory.dataChapters().map { it.durationSeconds }

        // Assert
        assertEquals(durations.size, durations.distinct().size)
        assertTrue("durations must be positive", durations.all { it > 0L })
    }

    @Test
    fun `stream urls are unroutable so accidental network io fails fast`() {
        // Arrange / Act
        val chapters = TestDataFactory.dataChapters()

        // Assert
        assertTrue(
            "fixtures must not point at a resolvable host",
            chapters.all { it.streamUrl.contains(".invalid/") }
        )
        assertEquals(chapters.size, chapters.map { it.streamUrl }.distinct().size)
        assertTrue("fixtures must not pretend to be downloaded", chapters.none { it.isDownloaded })
    }

    @Test
    fun `seeded timestamps are frozen rather than wall clock`() {
        // Arrange / Act
        val progress = TestDataFactory.seedPlaybackProgress()
        val bookmarks = TestDataFactory.seedBookmarks()
        val sources = TestDataFactory.seedSources()
        val stats = TestDataFactory.seedListeningStats()

        // Assert
        assertTrue(progress.all { it.lastListenedAt == TestDataFactory.FIXED_CLOCK_MS })
        assertTrue(bookmarks.all { it.createdAt == TestDataFactory.FIXED_CLOCK_MS })
        assertTrue("sources must not carry wall-clock addedAt", sources.all { it.addedAt == TestDataFactory.FIXED_CLOCK_MS })
        assertTrue(bookmarks.none { it.id == 0L })
        assertEquals(listOf(TestDataFactory.FIXED_DATE_ISO), stats.map { it.dateIso })
        assertEquals(TestDataFactory.BOOK_COUNT, progress.size)
        // Two types x three books, one row per type per book, ids mirror sourceRow.
        assertEquals(6, sources.size)
        assertEquals(listOf("4read", "soundbooks"), sources.map { it.type }.distinct())
        assertTrue(sources.all { it.id == "${it.type}-${it.bookId}" })
    }

    @Test
    fun `chaptersFor rejects a book that is not part of the fixture set`() {
        // Arrange
        val alien = TestDataFactory.dataBooks().first().copy(id = "not-a-fixture")

        // Act
        val failure = runCatching { TestDataFactory.chaptersFor(alien) }

        // Assert
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `exactly one fixture book is marked downloaded`() {
        // Arrange / Act
        val books = TestDataFactory.dataBooks()

        // Assert
        assertEquals(1, books.count { it.isDownloaded })
        assertFalse("offline book must report full download progress", books.any {
            it.isDownloaded && it.downloadProgress < 1.0f
        })
    }
}
