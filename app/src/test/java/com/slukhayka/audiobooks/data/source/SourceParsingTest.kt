package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-35 T1 — table-driven tests for the shared pure-JVM parse helpers
 * (ogMeta / decodeEntities / titleFromSlug / parseDurationSeconds).
 * Expected values are independent literals (known-good inputs), never
 * recomputed by the helpers under test.
 */
class SourceParsingTest {

    @Test
    fun `ogMeta reads the property-first meta tag`() {
        val html = """<meta property="og:title" content="Трохи ненависті" />"""
        assertEquals("Трохи ненависті", ogMeta(html, "og:title"))
    }

    @Test
    fun `ogMeta reads the content-first meta tag`() {
        val html = """<meta content="Трохи ненависті" property="og:title">"""
        assertEquals("Трохи ненависті", ogMeta(html, "og:title"))
    }

    @Test
    fun `ogMeta stays null when the tag or the property is absent`() {
        assertNull(ogMeta("<html><head></head><body>x</body></html>", "og:title"))
        assertNull(ogMeta("""<meta property="og:description" content="x" />""", "og:title"))
    }

    @Test
    fun `decodeEntities decodes the supported entity set once`() {
        assertEquals(
            "Наталія Дев'ятко — \"Книга\" & «Світ»",
            decodeEntities("Наталія Дев&#039;ятко — &quot;Книга&quot; &amp; «Світ»")
        )
    }

    @Test
    fun `decodeEntities leaves unknown sequences untouched`() {
        assertEquals("&lt;tag&gt; &nbsp;", decodeEntities("&lt;tag&gt; &nbsp;"))
    }

    @Test
    fun `titleFromSlug replaces hyphens, trims and titlecases the first letter`() {
        assertEquals("Zahublena stinka", titleFromSlug("zahublena-stinka"))
        assertEquals("Kobzar", titleFromSlug("kobzar"))
        assertEquals("Soniachna mashyna", titleFromSlug("soniachna-mashyna"))
        assertEquals("Кобзар", titleFromSlug(" Кобзар "))
    }

    @Test
    fun `parseDurationSeconds reads hh mm ss`() {
        assertEquals(16 * 3600L + 41 * 60L + 11L, parseDurationSeconds("16:41:11"))
        assertEquals(9 * 3600L + 28 * 60L + 9L, parseDurationSeconds("09:28:09"))
    }

    @Test
    fun `parseDurationSeconds reads mm ss`() {
        assertEquals(53 * 60L + 42L, parseDurationSeconds("53:42"))
    }

    @Test
    fun `parseDurationSeconds stays null for non-duration input`() {
        assertNull(parseDurationSeconds(""))
        assertNull(parseDurationSeconds("10"))
        assertNull(parseDurationSeconds("1:2:3:4"))
        assertNull(parseDurationSeconds("abc"))
        assertNull(parseDurationSeconds("16:41:xx"))
    }
}
