package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #752 — the Listening State seam. The store must not clobber the
 * per-book preferred speed or the completion flag on every progress write,
 * and the manual «Прослухано» mark must set the flag without touching the
 * position. Expected values come from the spec, not from the code.
 */
class ListeningStateStoreTest {

    private val book = TestDataFactory.dataBooks()[0]
    private val dao = FakeAudiobookDao(
        books = TestDataFactory.dataBooks(),
        chapters = TestDataFactory.dataChapters()
    )
    private val store = ListeningStateStore(dao)

    @Test
    fun `saving progress preserves the book's preferred speed`() = runTest {
        store.updateProgress(book.id, chapterIndex = 0, positionSeconds = 10L)
        // Seed the preference the way a real save (or an earlier session)
        // leaves it: on the Listening State row itself.
        val seeded = dao.getPlaybackProgressSync(book.id)!!
        dao.savePlaybackProgress(seeded.copy(preferredSpeed = 1.5f))

        store.updateProgress(book.id, chapterIndex = 1, positionSeconds = 60L)

        val row = dao.getPlaybackProgressSync(book.id)
        assertEquals(1.5f, row?.preferredSpeed ?: 0f, 0.001f)
        assertEquals(60L, row?.currentPositionSeconds)
    }

    @Test
    fun `saving progress preserves completion`() = runTest {
        store.setCompleted(book.id, true)

        store.updateProgress(book.id, chapterIndex = 1, positionSeconds = 60L)

        assertTrue(dao.getPlaybackProgressSync(book.id)?.isCompleted == true)
    }

    @Test
    fun `marking listened sets the flag and keeps the position`() = runTest {
        store.updateProgress(book.id, chapterIndex = 1, positionSeconds = 120L)

        store.setCompleted(book.id, true)

        val marked = dao.getPlaybackProgressSync(book.id)
        assertTrue(marked?.isCompleted == true)
        assertEquals(120L, marked?.currentPositionSeconds)

        store.setCompleted(book.id, false)
        assertFalse(dao.getPlaybackProgressSync(book.id)?.isCompleted == true)
    }

    @Test
    fun `marking listened works before any progress exists`() = runTest {
        store.setCompleted(book.id, true)

        assertTrue(dao.getPlaybackProgressSync(book.id)?.isCompleted == true)
    }
}
