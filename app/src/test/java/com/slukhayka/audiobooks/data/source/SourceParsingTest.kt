package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `stripTags removes tags or replaces them with the given string`() {
        assertEquals("текст", stripTags("<p>те<em>к</em>ст</p>"))
        assertEquals(" a   b ", stripTags("<i>a</i> <b>b</b>", " "))
    }

    @Test
    fun `cutAtEarliestMarker cuts at the earliest position across markers`() {
        // #267: the promo paragraph holds «Телеграм канал автора t.me/…»
        // BEFORE «Подякувати» — list order would keep the promo, position wins.
        val text = "Телеграм канал автора t.me/x Подякувати диктору"
        assertEquals("", cutAtEarliestMarker(text, listOf("Теги", "Подякувати", "Телеграм канал")))
        assertEquals("Блурб.", cutAtEarliestMarker("Блурб. Теги# детектив", listOf("Подякувати", "Теги#")))
        assertNull(cutAtEarliestMarker("Чистий текст", listOf("Теги", "PayPal")))
    }

    @Test
    fun `itempropDescriptionContainer bounds the container past nested divs`() {
        val html = """<div itemprop="description"><p>Абзац.</p><div class="quote">Цитата</div><p>Ще.</p></div><p>Після контейнера</p>"""
        val (bodyStart, close) = itempropDescriptionContainer(html)!!
        assertTrue(html.substring(bodyStart, close).contains("Цитата"))
        assertTrue(!html.substring(bodyStart, close).contains("Після контейнера"))
    }

    @Test
    fun `itempropDescriptionContainer stays null when absent or unbalanced`() {
        assertNull(itempropDescriptionContainer("<p>без контейнера</p>"))
        assertNull(itempropDescriptionContainer("""<div itemprop="description"><div>незакрито"""))
    }
}
