package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntryOriginPolicy.TriageAction
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0047 / #867 — the «Імпортоване» queue over the fake Room carrier. */
class ImportedLibraryEntriesTest {

    private val dao = FakeAudiobookDao()
    private val queue = ImportedLibraryEntries(dao)

    private fun entry(id: String, origin: LibraryEntryOrigin, createdAt: Long) =
        LibraryEntryEntity(id = id, workId = "work-$id", createdAt = createdAt, origin = origin.name)

    @Test
    fun `the queue holds exactly the unrecovered rows, newest first`() = runBlocking {
        dao.seedLibraryEntry(entry("old", LibraryEntryOrigin.UNKNOWN, 100))
        dao.seedLibraryEntry(entry("new", LibraryEntryOrigin.UNKNOWN, 300))
        dao.seedLibraryEntry(entry("saved", LibraryEntryOrigin.EXPLICIT_SAVE, 200))
        dao.seedLibraryEntry(entry("auto", LibraryEntryOrigin.AUTO_SEED, 400))

        assertEquals(listOf("new", "old"), queue.pending().map { it.id })
    }

    @Test
    fun `confirming makes the row personal and empties its place in the queue`() = runBlocking {
        dao.seedLibraryEntry(entry("unknown-1", LibraryEntryOrigin.UNKNOWN, 100))

        assertTrue(queue.triage("unknown-1", TriageAction.CONFIRM_PERSONAL))

        val row = dao.libraryEntryById("unknown-1")!!
        assertEquals("EXPLICIT_SAVE", row.origin)
        assertEquals("the link itself stays; only its origin changed", "work-unknown-1", row.workId)
        assertTrue("the queue is empty again", queue.pending().isEmpty())
    }

    @Test
    fun `removing drops the link and nothing else`() = runBlocking {
        dao.seedLibraryEntry(entry("unknown-1", LibraryEntryOrigin.UNKNOWN, 100))

        assertTrue(queue.triage("unknown-1", TriageAction.REMOVE_LINK))

        assertNull("the link is gone", dao.libraryEntryById("unknown-1"))
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun `a row that is not in the queue is never silently decided`() = runBlocking {
        dao.seedLibraryEntry(entry("saved", LibraryEntryOrigin.EXPLICIT_SAVE, 100))

        assertFalse(queue.triage("saved", TriageAction.REMOVE_LINK))
        assertFalse(queue.triage("missing", TriageAction.CONFIRM_PERSONAL))
        assertTrue("an explicit row is untouched", dao.libraryEntryById("saved") != null)
    }

    @Test
    fun `a fully triaged library leaves the subsection EMPTY`() = runBlocking {
        dao.seedLibraryEntry(entry("a", LibraryEntryOrigin.UNKNOWN, 100))
        dao.seedLibraryEntry(entry("b", LibraryEntryOrigin.UNKNOWN, 200))

        queue.triage("a", TriageAction.CONFIRM_PERSONAL)
        queue.triage("b", TriageAction.REMOVE_LINK)

        assertTrue(queue.pending().isEmpty())
    }
}
