package com.slukhayka.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for the one Edition-language normalizer (#405).
 *
 * The mapping is deliberately bounded: only KNOWN languages normalize, and
 * anything else is null (the caller stores "" = unknown). A wrong guess would
 * poison the language filter for a monoglot listener, so the safe default is
 * "I don't know" — never "probably English" (US17).
 */
class LanguageCodeTest {

    @Test
    fun `english names and codes normalize to en`() {
        assertEquals("en", LanguageCode.normalize("English"))
        assertEquals("en", LanguageCode.normalize("english"))
        assertEquals("en", LanguageCode.normalize("EN"))
        assertEquals("en", LanguageCode.normalize("eng"))
        assertEquals("en", LanguageCode.normalize("en-US"))
        assertEquals("en", LanguageCode.normalize("en_US"))
        assertEquals("en", LanguageCode.normalize(" en "))
    }

    @Test
    fun `ukrainian names and codes normalize to uk`() {
        assertEquals("uk", LanguageCode.normalize("Ukrainian"))
        assertEquals("uk", LanguageCode.normalize("ukrainian"))
        assertEquals("uk", LanguageCode.normalize("ukr"))
        assertEquals("uk", LanguageCode.normalize("uk-UA"))
        assertEquals("uk", LanguageCode.normalize("uk"))
    }

    @Test
    fun `other known languages normalize to their two-letter tag`() {
        assertEquals("de", LanguageCode.normalize("German"))
        assertEquals("de", LanguageCode.normalize("deu"))
        assertEquals("de", LanguageCode.normalize("de-DE"))
        assertEquals("fr", LanguageCode.normalize("French"))
        assertEquals("fr", LanguageCode.normalize("fra"))
        assertEquals("es", LanguageCode.normalize("Spanish"))
        assertEquals("ru", LanguageCode.normalize("Russian"))
        assertEquals("pl", LanguageCode.normalize("Polish"))
        assertEquals("it", LanguageCode.normalize("Italian"))
        assertEquals("zh", LanguageCode.normalize("Chinese"))
    }

    @Test
    fun `unknown and blank claims are null - never guessed`() {
        assertNull(LanguageCode.normalize(null))
        assertNull(LanguageCode.normalize(""))
        assertNull(LanguageCode.normalize("  "))
        assertNull(LanguageCode.normalize("Klingon"))
        assertNull(LanguageCode.normalize("??? "))
        assertNull(LanguageCode.normalize("xx"))
        assertNull(LanguageCode.normalize("Ukrainian Canadian"))
    }

    @Test
    fun `constants are the canonical tags`() {
        assertEquals("uk", LanguageCode.UKRAINIAN)
        assertEquals("en", LanguageCode.ENGLISH)
    }
}
