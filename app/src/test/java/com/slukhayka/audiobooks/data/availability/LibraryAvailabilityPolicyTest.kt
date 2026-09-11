package com.slukhayka.audiobooks.data.availability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0042 §1 (spec-56 T1) — the pure availability state policy: a Work with
 * available audio is never marked; refusal and "not found" are distinct; a
 * "checked" verdict is never shown without an observation time.
 */
class LibraryAvailabilityPolicyTest {

    @Test
    fun `a work with available audio is never marked`() {
        assertNull(
            LibraryAvailabilityPolicy.viewFor(
                hasAvailableAudio = true,
                refusedSourceId = null,
                stored = null
            )
        )
        assertNull(
            LibraryAvailabilityPolicy.viewFor(
                hasAvailableAudio = true,
                refusedSourceId = null,
                stored = AvailabilityVerdict(AvailabilityStatus.NOT_FOUND, observedAtMs = 1L)
            )
        )
    }

    @Test
    fun `an unchecked problem work is checking, not a fabricated verdict`() {
        val view = LibraryAvailabilityPolicy.viewFor(
            hasAvailableAudio = false,
            refusedSourceId = null,
            stored = null
        )
        assertEquals(AvailabilityStatus.CHECKING, view?.status)
        assertEquals(0L, view?.observedAtMs)
    }

    @Test
    fun `not found renders with its observation time`() {
        val view = LibraryAvailabilityPolicy.viewFor(
            hasAvailableAudio = false,
            refusedSourceId = null,
            stored = AvailabilityVerdict(AvailabilityStatus.NOT_FOUND, observedAtMs = 1_700_000_000_000L)
        )
        assertEquals(AvailabilityStatus.NOT_FOUND, view?.status)
        assertEquals(1_700_000_000_000L, view?.observedAtMs)
    }

    @Test
    fun `a checked verdict without a time degrades to checking`() {
        val notFound = LibraryAvailabilityPolicy.viewFor(
            hasAvailableAudio = false,
            refusedSourceId = null,
            stored = AvailabilityVerdict(AvailabilityStatus.NOT_FOUND, observedAtMs = 0L)
        )
        assertEquals(AvailabilityStatus.CHECKING, notFound?.status)

        val found = LibraryAvailabilityPolicy.viewFor(
            hasAvailableAudio = false,
            refusedSourceId = null,
            stored = AvailabilityVerdict(AvailabilityStatus.FOUND, sourceId = "sluhayua", observedAtMs = 0L)
        )
        assertEquals(AvailabilityStatus.CHECKING, found?.status)
    }

    @Test
    fun `found carries the source, but a blank source is only checking`() {
        val found = LibraryAvailabilityPolicy.viewFor(
            hasAvailableAudio = false,
            refusedSourceId = null,
            stored = AvailabilityVerdict(
                AvailabilityStatus.FOUND,
                sourceId = "sluhayua",
                observedAtMs = 42L
            )
        )
        assertEquals(AvailabilityStatus.FOUND, found?.status)
        assertEquals("sluhayua", found?.sourceId)

        val blankSource = LibraryAvailabilityPolicy.viewFor(
            hasAvailableAudio = false,
            refusedSourceId = null,
            stored = AvailabilityVerdict(AvailabilityStatus.FOUND, sourceId = "", observedAtMs = 42L)
        )
        assertEquals(AvailabilityStatus.CHECKING, blankSource?.status)
    }

    @Test
    fun `refusal is distinct from not found and needs no time`() {
        val view = LibraryAvailabilityPolicy.viewFor(
            hasAvailableAudio = false,
            refusedSourceId = "4read",
            stored = null
        )
        assertEquals(AvailabilityStatus.REFUSED, view?.status)
        assertEquals("4read", view?.sourceId)
        assertEquals(0L, view?.observedAtMs)
    }

    @Test
    fun `a blank source url has no available audio`() {
        assertFalse(LibraryAvailabilityPolicy.hasAvailableAudio("", emptySet()))
        assertFalse(LibraryAvailabilityPolicy.hasAvailableAudio("", setOf("4read")))
    }

    @Test
    fun `a refused source url has no available audio and reports the source`() {
        val url = "https://4read.org/book-1"
        assertFalse(LibraryAvailabilityPolicy.hasAvailableAudio(url, setOf("4read")))
        assertEquals("4read", LibraryAvailabilityPolicy.refusedSourceId(url, setOf("4read")))
        assertTrue(LibraryAvailabilityPolicy.hasAvailableAudio(url, emptySet()))
    }
}
