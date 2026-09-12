package com.slukhayka.audiobooks.data.editions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #530 — the same-Edition auto-match rule: only a full, compatible fact set
 * auto-merges; an unknown narrator, a language mismatch, a duration conflict
 * or incompatible chapters always degrade to a confirmed offer instead.
 */
class EditionMatchPolicyTest {

    private val observed = EditionMatchFacts(
        workKey = "лісова пісня|леся українка",
        language = "uk",
        narrator = "Олександр",
        durationSeconds = 7_200L,
        chapterCount = 12
    )

    private fun candidate(
        workKey: String = observed.workKey,
        language: String = "uk",
        narrator: String = "Олександр",
        durationSeconds: Long? = 7_200L,
        chapterCount: Int? = 12
    ) = EditionMatchFacts(workKey, language, narrator, durationSeconds, chapterCount)

    @Test
    fun `a fully compatible candidate is the same edition`() {
        assertEquals(EditionMatchVerdict.SAME_EDITION, EditionMatchPolicy.verdict(observed, candidate()))
        // The tolerance is inclusive at exactly 2%.
        assertEquals(
            EditionMatchVerdict.SAME_EDITION,
            EditionMatchPolicy.verdict(observed, candidate(durationSeconds = 7_344L))
        )
    }

    @Test
    fun `a different work is incompatible`() {
        assertEquals(
            EditionMatchVerdict.INCOMPATIBLE,
            EditionMatchPolicy.verdict(observed, candidate(workKey = "кобзар|тарас шевченко"))
        )
        assertEquals(
            EditionMatchVerdict.INCOMPATIBLE,
            EditionMatchPolicy.verdict(observed, candidate(workKey = ""))
        )
    }

    @Test
    fun `an unknown narrator never auto-merges`() {
        assertEquals(EditionMatchVerdict.OTHER_EDITION, EditionMatchPolicy.verdict(observed, candidate(narrator = "")))
        assertEquals(
            EditionMatchVerdict.OTHER_EDITION,
            EditionMatchPolicy.verdict(observed.copy(narrator = ""), candidate())
        )
    }

    @Test
    fun `a different narrator is another edition`() {
        assertEquals(
            EditionMatchVerdict.OTHER_EDITION,
            EditionMatchPolicy.verdict(observed, candidate(narrator = "Інший голос"))
        )
    }

    @Test
    fun `the narrator key ignores case, punctuation and the reading boilerplate`() {
        assertEquals(
            EditionMatchPolicy.narratorKey("Олександр"),
            EditionMatchPolicy.narratorKey("  Читає: ОЛЕКСАНДР  ")
        )
        assertTrue(EditionMatchPolicy.narratorKey("  ").isEmpty())
        assertEquals(
            EditionMatchVerdict.SAME_EDITION,
            EditionMatchPolicy.verdict(observed, candidate(narrator = "Читає Олександр"))
        )
    }

    @Test
    fun `a language mismatch never auto-merges`() {
        assertEquals(
            EditionMatchVerdict.OTHER_EDITION,
            EditionMatchPolicy.verdict(observed, candidate(language = "en"))
        )
        assertEquals(
            EditionMatchVerdict.OTHER_EDITION,
            EditionMatchPolicy.verdict(observed, candidate(language = ""))
        )
    }

    @Test
    fun `a duration conflict never auto-merges`() {
        // Outside the 2% window.
        assertEquals(
            EditionMatchVerdict.OTHER_EDITION,
            EditionMatchPolicy.verdict(observed, candidate(durationSeconds = 7_500L))
        )
        // An unknown duration is not a confirmation either.
        assertEquals(
            EditionMatchVerdict.OTHER_EDITION,
            EditionMatchPolicy.verdict(observed, candidate(durationSeconds = null))
        )
        assertEquals(
            EditionMatchVerdict.OTHER_EDITION,
            EditionMatchPolicy.verdict(observed.copy(durationSeconds = null), candidate())
        )
    }

    @Test
    fun `incompatible or unknown chapters never auto-merge`() {
        assertEquals(
            EditionMatchVerdict.OTHER_EDITION,
            EditionMatchPolicy.verdict(observed, candidate(chapterCount = 13))
        )
        assertEquals(
            EditionMatchVerdict.OTHER_EDITION,
            EditionMatchPolicy.verdict(observed, candidate(chapterCount = null))
        )
    }

    @Test
    fun `duration compatibility is honest about its bounds`() {
        assertTrue(EditionMatchPolicy.durationCompatible(7_200L, 7_200L))
        assertTrue(EditionMatchPolicy.durationCompatible(7_200L, 7_343L))
        assertFalse(EditionMatchPolicy.durationCompatible(7_200L, 7_345L))
        assertFalse(EditionMatchPolicy.durationCompatible(null, 7_200L))
        assertFalse(EditionMatchPolicy.durationCompatible(0L, 7_200L))
    }
}
