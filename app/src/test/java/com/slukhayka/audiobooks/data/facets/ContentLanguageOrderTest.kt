package com.slukhayka.audiobooks.data.facets

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec-51 (#742) — one ordering rule shared by the filter screen, the chip and
 * the First Language Choice: Ukrainian first, English second, the rest
 * alphabetically (the web `availableLanguagesOf` rule, mirrored).
 */
class ContentLanguageOrderTest {

    @Test
    fun `ukrainian first, english second, the rest alphabetically`() {
        assertEquals(
            listOf("uk", "en", "de", "fr", "pl"),
            orderContentLanguages(listOf("pl", "fr", "en", "de", "uk"))
        )
    }

    @Test
    fun `duplicates and blank entries never reach a list`() {
        assertEquals(listOf("uk", "de"), orderContentLanguages(listOf("de", "uk", "de", "", "  ")))
    }
}
