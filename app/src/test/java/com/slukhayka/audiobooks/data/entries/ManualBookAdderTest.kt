package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0046/0047 / #870 — what a manual add actually WRITES. */
class ManualBookAdderTest {

    private val dao = FakeAudiobookDao()
    private val adder = ManualBookAdder(dao)

    private fun request(
        format: ReadingFormat = ReadingFormat.PAPER,
        editionId: String? = null,
        importedOwnFile: Boolean = false,
        wantsToRead: Boolean = false
    ) = ManualBookAddRequest(
        title = "Кобзар",
        author = "Тарас Шевченко",
        format = format,
        editionId = editionId,
        importedOwnFile = importedOwnFile,
        wantsToRead = wantsToRead,
        now = 1_700_000_000_000L
    )

    @Test
    fun `a paper add writes the link with its EXPLICIT origin and no invented pass`() = runBlocking {
        val result = adder.add(request()) as ManualBookAdder.Result.Added

        assertEquals(LibraryEntryOrigin.EXPLICIT_SAVE, result.origin)
        assertEquals(LibraryEntryOrigin.EXPLICIT_SAVE, adder.originOf(result.libraryEntryId))
        assertEquals(
            "the link hangs off a real Work",
            ManualBookAddPolicy.workKey(request()),
            dao.libraryEntryById(result.libraryEntryId)!!.workId
        )
        assertNull("saying nothing creates no pass", result.readthroughId)
        assertTrue(
            "and no pass row appeared",
            dao.readthroughsForWork(result.workId).isEmpty()
        )
    }

    @Test
    fun `the listener's own import is recorded as the OTHER explicit action`() = runBlocking {
        val result = adder.add(request(importedOwnFile = true)) as ManualBookAdder.Result.Added

        assertEquals(LibraryEntryOrigin.EXPLICIT_IMPORT, result.origin)
        assertTrue(LibraryEntryOriginPolicy.isPersonal(adder.originOf(result.libraryEntryId)!!))
    }

    @Test
    fun `an explicit «I want to read» writes the pass with the format's own units`() = runBlocking {
        val result = adder.add(request(wantsToRead = true)) as ManualBookAdder.Result.Added

        assertNotNull(result.readthroughId)
        val pass = dao.readthroughsForWork(result.workId).single()
        assertEquals("PAPER", pass.format)
        assertEquals("IN_PROGRESS", pass.state)
        assertEquals("PAGES", pass.unit)
        assertNull("a paper pass names no Edition", pass.editionId)
        assertEquals("it belongs to the SAME link", result.libraryEntryId, pass.libraryEntryId)
    }

    @Test
    fun `an audio add points at the existing Edition and nothing is invented without it`() = runBlocking {
        val refused = adder.add(request(format = ReadingFormat.AUDIO))
        assertTrue(refused is ManualBookAdder.Result.Refused)

        val added = adder.add(
            request(format = ReadingFormat.AUDIO, editionId = "edition-1", wantsToRead = true)
        ) as ManualBookAdder.Result.Added
        val pass = dao.readthroughsForWork(added.workId).single()
        assertEquals("edition-1", pass.editionId)
        assertEquals("SECONDS", pass.unit)
    }

    @Test
    fun `a paper or e-book add is refused when it tries to name an Edition`() = runBlocking {
        val result = adder.add(request(editionId = "edition-1"))

        assertEquals(
            ManualBookAddPolicy.REASON_EDITION_NOT_ALLOWED,
            (result as ManualBookAdder.Result.Refused).reason
        )
        assertNull("nothing was written", dao.libraryEntryById(ManualBookAddPolicy.libraryEntryId(request())))
    }

    @Test
    fun `adding the same book twice keeps ONE link`() = runBlocking {
        val first = adder.add(request(wantsToRead = true)) as ManualBookAdder.Result.Added
        val second = adder.add(request(wantsToRead = true)) as ManualBookAdder.Result.Added

        assertEquals(first.libraryEntryId, second.libraryEntryId)
        assertEquals(first.readthroughId, second.readthroughId)
        assertEquals("one pass, not two", 1, dao.readthroughsForWork(first.workId).size)
    }
}
