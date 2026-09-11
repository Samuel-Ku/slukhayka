package com.slukhayka.audiobooks.ui.screens

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec-51 (#742) — the wider catalogue renders through platform display names
 * in the App Locale (no 48-language resource duplication), and a tag the
 * platform cannot name degrades to its own code — never a blank row.
 */
class ContentLanguageLabelsTest {

    @Test
    fun `the wider languages render in the given locale`() {
        assertEquals("Німецька", contentLanguageDisplayName("de", Locale.forLanguageTag("uk")))
        assertEquals("German", contentLanguageDisplayName("de", Locale.forLanguageTag("en")))
    }

    @Test
    fun `an unrecognized tag degrades to its own code`() {
        assertEquals("xx", contentLanguageDisplayName("xx", Locale.forLanguageTag("uk")))
    }
}
