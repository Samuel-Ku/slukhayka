package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.entries.LibraryEntryOriginPolicy.TriageAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0047 / #867 — the «Імпортоване» rules: no guesswork, and it empties. */
class LibraryEntryOriginPolicyTest {

    @Test
    fun `personal surfaces build on EXPLICIT intent only`() {
        assertTrue(LibraryEntryOriginPolicy.isPersonal(LibraryEntryOrigin.EXPLICIT_SAVE))
        assertTrue(LibraryEntryOriginPolicy.isPersonal(LibraryEntryOrigin.EXPLICIT_IMPORT))

        assertFalse(LibraryEntryOriginPolicy.isPersonal(LibraryEntryOrigin.AUTO_SEED))
        assertFalse(LibraryEntryOriginPolicy.isPersonal(LibraryEntryOrigin.CATALOG_SYNC))
        assertFalse(LibraryEntryOriginPolicy.isPersonal(LibraryEntryOrigin.UNKNOWN))
    }

    @Test
    fun `an unknown origin is NOT declared an auto-seed by guesswork`() {
        assertTrue(LibraryEntryOriginPolicy.needsTriage(LibraryEntryOrigin.UNKNOWN))
        assertFalse(
            "an automatic pass is a KNOWN fact, not something to triage",
            LibraryEntryOriginPolicy.needsTriage(LibraryEntryOrigin.AUTO_SEED)
        )
        assertFalse(LibraryEntryOriginPolicy.needsTriage(LibraryEntryOrigin.CATALOG_SYNC))
        assertFalse(LibraryEntryOriginPolicy.needsTriage(LibraryEntryOrigin.EXPLICIT_SAVE))
    }

    @Test
    fun `the triage subsection holds exactly the unrecovered rows, in the listener's order`() {
        val rows = listOf("unknown-1", "saved", "auto", "unknown-2")
        val origins = mapOf(
            "unknown-1" to LibraryEntryOrigin.UNKNOWN,
            "saved" to LibraryEntryOrigin.EXPLICIT_SAVE,
            "auto" to LibraryEntryOrigin.AUTO_SEED,
            "unknown-2" to LibraryEntryOrigin.UNKNOWN
        )

        val triage = LibraryEntryOriginPolicy.triageSection(rows) { origins.getValue(it) }

        assertEquals(listOf("unknown-1", "unknown-2"), triage)
    }

    @Test
    fun `confirming makes the row personal and empties the bucket`() {
        val confirmed = LibraryEntryOriginPolicy.afterTriage(
            LibraryEntryOrigin.UNKNOWN,
            TriageAction.CONFIRM_PERSONAL
        )

        assertEquals(LibraryEntryOrigin.EXPLICIT_SAVE, confirmed)
        assertTrue(LibraryEntryOriginPolicy.isPersonal(confirmed!!))
        assertFalse(LibraryEntryOriginPolicy.needsTriage(confirmed))
    }

    @Test
    fun `removing drops the LINK and nothing else`() {
        assertNull(
            "the link is gone; the Work/Edition/Source stay (ADR-0009)",
            LibraryEntryOriginPolicy.afterTriage(LibraryEntryOrigin.UNKNOWN, TriageAction.REMOVE_LINK)
        )
    }

    @Test
    fun `a fully triaged library has an EMPTY subsection`() {
        val origins = listOf(
            LibraryEntryOrigin.EXPLICIT_SAVE,
            LibraryEntryOrigin.EXPLICIT_IMPORT,
            LibraryEntryOrigin.AUTO_SEED,
            LibraryEntryOrigin.CATALOG_SYNC
        )

        val triage = LibraryEntryOriginPolicy.triageSection(origins) { it }

        assertTrue("nothing is left to decide", triage.isEmpty())
    }
}
