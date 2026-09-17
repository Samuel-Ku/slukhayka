package com.slukhayka.audiobooks.data.entries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0046/0047 / #870 — any format, without a fictitious Edition or a guessed "want". */
class ManualBookAddPolicyTest {

    private fun request(
        format: ReadingFormat = ReadingFormat.PAPER,
        editionId: String? = null,
        importedOwnFile: Boolean = false,
        wantsToRead: Boolean = false,
        title: String = "Кобзар",
        author: String = "Тарас Шевченко"
    ) = ManualBookAddRequest(
        title = title,
        author = author,
        format = format,
        editionId = editionId,
        importedOwnFile = importedOwnFile,
        wantsToRead = wantsToRead,
        now = 1_700_000_000_000L
    )

    private fun plan(request: ManualBookAddRequest) =
        ManualBookAddPolicy.plan(request) as ManualBookAddPlan.Add

    @Test
    fun `a paper book is added with NO edition and NO invented state`() {
        val plan = plan(request())

        assertEquals(ReadingFormat.PAPER, plan.format)
        assertNull("a format is not an Edition", plan.readthrough?.editionId)
        assertNull(
            "saying nothing must not become «Хочу прочитати»",
            plan.readthrough
        )
    }

    @Test
    fun `the listener's own «I want to read» creates the pass - and only then`() {
        val plan = plan(request(wantsToRead = true))

        val pass = plan.readthrough!!
        assertEquals(ReadingState.IN_PROGRESS, pass.state)
        assertEquals(ReadingUnit.PAGES, pass.units.unit)
        assertNull("still no Edition for paper", pass.editionId)
    }

    @Test
    fun `an e-book uses its own unit and creates no Edition either`() {
        val plan = plan(request(format = ReadingFormat.EBOOK, wantsToRead = true))

        assertEquals(ReadingUnit.PERCENT, plan.readthrough!!.units.unit)
        assertNull(plan.readthrough.editionId)
    }

    @Test
    fun `an audio add IS an existing narration, so without an Edition it is refused`() {
        val refused = ManualBookAddPolicy.plan(request(format = ReadingFormat.AUDIO))

        assertEquals(
            ManualBookAddPolicy.REASON_AUDIO_NEEDS_EDITION,
            (refused as ManualBookAddPlan.Refused).reason
        )
    }

    @Test
    fun `a paper or e-book add never names an Edition`() {
        val refused = ManualBookAddPolicy.plan(request(editionId = "edition-1"))

        assertEquals(
            "a format is not an Edition",
            ManualBookAddPolicy.REASON_EDITION_NOT_ALLOWED,
            (refused as ManualBookAddPlan.Refused).reason
        )
    }

    @Test
    fun `the origin is the listener's EXPLICIT action, never an auto-seed`() {
        assertEquals(
            LibraryEntryOrigin.EXPLICIT_SAVE,
            plan(request()).origin
        )
        assertEquals(
            "an imported own file is the other explicit action",
            LibraryEntryOrigin.EXPLICIT_IMPORT,
            plan(request(importedOwnFile = true)).origin
        )
        assertTrue(
            "and it is therefore personal",
            LibraryEntryOriginPolicy.isPersonal(plan(request(importedOwnFile = true)).origin)
        )
    }

    @Test
    fun `an audio add points its pass at the existing Edition`() {
        val plan = plan(request(format = ReadingFormat.AUDIO, editionId = "edition-1", wantsToRead = true))

        assertEquals("edition-1", plan.readthrough!!.editionId)
        assertEquals(ReadingUnit.SECONDS, plan.readthrough.units.unit)
    }

    @Test
    fun `a nameless add is refused instead of guessed`() {
        assertEquals(
            ManualBookAddPolicy.REASON_NO_IDENTITY,
            (ManualBookAddPolicy.plan(request(title = "  ")) as ManualBookAddPlan.Refused).reason
        )
        assertEquals(
            ManualBookAddPolicy.REASON_NO_IDENTITY,
            (ManualBookAddPolicy.plan(request(author = "")) as ManualBookAddPlan.Refused).reason
        )
    }

    @Test
    fun `the same book added twice derives the same ids - an idempotent add`() {
        assertEquals(
            ManualBookAddPolicy.libraryEntryId(request()),
            ManualBookAddPolicy.libraryEntryId(request())
        )
    }
}
