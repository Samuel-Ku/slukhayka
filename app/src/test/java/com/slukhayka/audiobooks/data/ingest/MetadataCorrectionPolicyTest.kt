package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-53 T7 — the correction policy: display claims only, one hard rule
 * (a blank title is refused), and a cleared author/narrator really clears.
 */
class MetadataCorrectionPolicyTest {

    private fun corrected(
        title: String? = null,
        author: String? = null,
        narrator: String? = null,
        currentTitle: String = "Кривий розбір",
        currentAuthor: String = "Невідомий",
        currentNarrator: String = "Диктор"
    ): MetadataCorrectionPolicy.Correction {
        val outcome = MetadataCorrectionPolicy.apply(
            currentTitle = currentTitle,
            currentAuthor = currentAuthor,
            currentNarrator = currentNarrator,
            edit = MetadataCorrectionPolicy.Edit(title = title, author = author, narrator = narrator)
        )
        assertTrue("expected a correction, got $outcome", outcome is MetadataCorrectionPolicy.Outcome.Corrected)
        return (outcome as MetadataCorrectionPolicy.Outcome.Corrected).correction
    }

    @Test
    fun `a corrected title is trimmed and the other claims stay`() {
        val correction = corrected(title = "  Справжня назва  ")

        assertEquals("Справжня назва", correction.title)
        assertEquals("Невідомий", correction.author)
        assertEquals("Диктор", correction.narrator)
    }

    @Test
    fun `a cleared author really clears the invented claim`() {
        val correction = corrected(author = "   ")

        assertEquals("", correction.author)
        assertEquals("Кривий розбір", correction.title)
    }

    @Test
    fun `a blank title is refused — the card must keep its name`() {
        val outcome = MetadataCorrectionPolicy.apply(
            currentTitle = "Кривий розбір",
            currentAuthor = "Невідомий",
            currentNarrator = "Диктор",
            edit = MetadataCorrectionPolicy.Edit(title = "   ")
        )

        assertEquals(
            MetadataCorrectionPolicy.Outcome.Refused(MetadataCorrectionPolicy.Refusal.BLANK_TITLE),
            outcome
        )
    }

    @Test
    fun `an unchanged edit is refused instead of writing the same row`() {
        val outcome = MetadataCorrectionPolicy.apply(
            currentTitle = "Кривий розбір",
            currentAuthor = "Невідомий",
            currentNarrator = "Диктор",
            edit = MetadataCorrectionPolicy.Edit(
                title = "Кривий розбір",
                author = "Невідомий",
                narrator = "Диктор"
            )
        )

        assertEquals(
            MetadataCorrectionPolicy.Outcome.Refused(MetadataCorrectionPolicy.Refusal.NOTHING_TO_CHANGE),
            outcome
        )
    }

    @Test
    fun `all three claims can be replaced at once`() {
        val correction = corrected(
            title = "Нова назва",
            author = "Справжній автор",
            narrator = "Справжня начитка"
        )

        assertEquals(
            MetadataCorrectionPolicy.Correction("Нова назва", "Справжній автор", "Справжня начитка"),
            correction
        )
    }
}
