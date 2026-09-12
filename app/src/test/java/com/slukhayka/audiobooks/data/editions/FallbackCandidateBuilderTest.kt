package com.slukhayka.audiobooks.data.editions

import com.slukhayka.audiobooks.data.source.SourceAccessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #530 — the candidate builder: real Source rows become honestly classified,
 * ordered candidates; a skipped (refused or cooling-down) source is left out
 * of the offer without being deleted.
 */
class FallbackCandidateBuilderTest {

    private fun facts(
        sourceId: String,
        accessMode: SourceAccessMode = SourceAccessMode.DIRECT,
        sameEdition: Boolean = true,
        mappingSafe: Boolean = true,
        isLocal: Boolean = false
    ) = SourceAvailabilityFacts(sourceId, accessMode, sameEdition, mappingSafe, isLocal)

    @Test
    fun `the ordered offer follows local to confirmed other edition`() {
        val ordered = FallbackCandidateBuilder.build(
            facts = listOf(
                facts("other", sameEdition = false),
                facts("browser", accessMode = SourceAccessMode.BROWSER),
                facts("alt"),
                facts("current"),
                facts("local", isLocal = true)
            ),
            currentSourceId = "current"
        )

        assertEquals(
            listOf("local", "current", "alt", "browser", "other"),
            ordered.map { it.sourceId }
        )
        assertEquals(FallbackCandidateKind.LOCAL, ordered[0].kind)
        assertEquals(FallbackCandidateKind.CURRENT_DIRECT, ordered[1].kind)
        assertEquals(FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION, ordered[2].kind)
        assertEquals(FallbackCandidateKind.BROWSER, ordered[3].kind)
        assertEquals(FallbackCandidateKind.CONFIRMED_OTHER_EDITION, ordered[4].kind)
    }

    @Test
    fun `a source in cooldown or refused is left out, never deleted`() {
        val all = listOf(facts("current"), facts("alt"), facts("cold"))

        val offered = FallbackCandidateBuilder.build(
            facts = all,
            currentSourceId = "current",
            skippedSourceIds = setOf("cold")
        )

        assertEquals(listOf("current", "alt"), offered.map { it.sourceId })
        assertTrue("the facts list is untouched", all.any { it.sourceId == "cold" })
    }

    @Test
    fun `a direct alternate of the same edition may auto-start`() {
        val start = FallbackCandidateBuilder.autoStartable(
            facts = listOf(facts("alt")),
            currentSourceId = "gone"
        )

        assertEquals("alt", start?.sourceId)
    }

    @Test
    fun `another edition never starts without confirmation`() {
        assertNull(
            FallbackCandidateBuilder.autoStartable(
                facts = listOf(facts("other", sameEdition = false)),
                currentSourceId = "gone"
            )
        )
    }

    @Test
    fun `an unsafe mapping or a browser door never starts without confirmation`() {
        assertNull(
            FallbackCandidateBuilder.autoStartable(
                facts = listOf(facts("alt", mappingSafe = false)),
                currentSourceId = "gone"
            )
        )
        assertNull(
            FallbackCandidateBuilder.autoStartable(
                facts = listOf(facts("browser", accessMode = SourceAccessMode.BROWSER)),
                currentSourceId = "gone"
            )
        )
    }

    @Test
    fun `duplicate and blank source rows collapse to one candidate`() {
        val offered = FallbackCandidateBuilder.build(
            facts = listOf(facts("alt"), facts("alt"), facts("")),
            currentSourceId = null
        )

        assertEquals(listOf("alt"), offered.map { it.sourceId })
    }
}
