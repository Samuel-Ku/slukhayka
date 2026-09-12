package com.slukhayka.audiobooks.data.editions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #530 — the ONE fallback order and the "never switch silently" rule: only a
 * same-Edition candidate with a safe chapter mapping may start by itself.
 */
class FallbackCandidateOrderTest {

    private fun candidate(
        sourceId: String,
        kind: FallbackCandidateKind,
        verdict: EditionMatchVerdict = EditionMatchVerdict.SAME_EDITION,
        mappingSafe: Boolean = true
    ) = FallbackCandidate(sourceId, kind, verdict, mappingSafe)

    @Test
    fun `the candidates are ordered local to confirmed other edition`() {
        val ordered = FallbackCandidateOrder.ordered(
            listOf(
                candidate("other", FallbackCandidateKind.CONFIRMED_OTHER_EDITION, EditionMatchVerdict.OTHER_EDITION),
                candidate("browser", FallbackCandidateKind.BROWSER),
                candidate("alt", FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION),
                candidate("current", FallbackCandidateKind.CURRENT_DIRECT),
                candidate("local", FallbackCandidateKind.LOCAL)
            )
        )

        assertEquals(
            listOf("local", "current", "alt", "browser", "other"),
            ordered.map { it.sourceId }
        )
    }

    @Test
    fun `a same-Edition direct alternate starts without asking`() {
        val start = FallbackCandidateOrder.autoStartable(
            listOf(
                candidate("browser", FallbackCandidateKind.BROWSER),
                candidate("alt", FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION)
            )
        )

        assertEquals("alt", start?.sourceId)
        assertFalse(start!!.requiresConfirmation)
    }

    @Test
    fun `an unsafe chapter mapping never auto-starts`() {
        val start = FallbackCandidateOrder.autoStartable(
            listOf(
                candidate("alt", FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION, mappingSafe = false)
            )
        )

        assertNull("no safe mapping means no automatic switch", start)
    }

    @Test
    fun `another narration always asks first`() {
        val other = candidate(
            "other",
            FallbackCandidateKind.CONFIRMED_OTHER_EDITION,
            EditionMatchVerdict.OTHER_EDITION
        )
        val unproven = candidate(
            "alt",
            FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION,
            EditionMatchVerdict.OTHER_EDITION
        )

        assertTrue(other.requiresConfirmation)
        assertTrue("an unproven same-Edition claim asks too", unproven.requiresConfirmation)
        assertNull(FallbackCandidateOrder.autoStartable(listOf(other, unproven)))
    }

    @Test
    fun `a browser door always asks first even for the same edition`() {
        val browser = candidate("browser", FallbackCandidateKind.BROWSER)

        assertTrue(browser.requiresConfirmation)
        assertNull(FallbackCandidateOrder.autoStartable(listOf(browser)))
    }

    @Test
    fun `local and current never ask when the mapping is safe`() {
        assertFalse(candidate("local", FallbackCandidateKind.LOCAL).requiresConfirmation)
        assertFalse(candidate("current", FallbackCandidateKind.CURRENT_DIRECT).requiresConfirmation)
        assertTrue(
            candidate("local", FallbackCandidateKind.LOCAL, mappingSafe = false).requiresConfirmation
        )
    }

    @Test
    fun `an empty candidate list has nothing to start`() {
        assertNull(FallbackCandidateOrder.autoStartable(emptyList()))
        assertTrue(FallbackCandidateOrder.ordered(emptyList()).isEmpty())
    }
}
