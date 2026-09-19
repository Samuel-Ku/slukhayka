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
    fun `decodeEntities decodes the hexadecimal numeric entity a React page emits`() {
        // #964 — a Next.js/React server-rendered page escapes an apostrophe as
        // the HEX form `&#x27;` (chytaylo listing); the decimal-only set missed
        // it and the entity reached the Work title and its merge key.
        assertEquals("Ім'я тіні", decodeEntities("Ім&#x27;я тіні"))
        assertEquals("Ім'я тіні", decodeEntities("Ім&#X27;я тіні"))
        assertEquals("Дев'ятко", decodeEntities("Дев&#x27;ятко"))
        // A numeric spelling of `&` is decoded too — and the single pass never
        // re-reads its own output, so `&#x26;amp;` stays the literal `&amp;`.
        assertEquals("A&B", decodeEntities("A&#x26;B"))
        assertEquals("&amp;", decodeEntities("&#x26;amp;"))
    }

    @Test
    fun `decodeEntities leaves a clean title byte-identical`() {
        // The boundary: a title with no entity must survive unchanged.
        assertEquals("Ім'я тіні", decodeEntities("Ім'я тіні"))
        assertEquals("Айя Нея — Ім'я тіні (Книга 1)", decodeEntities("Айя Нея — Ім'я тіні (Книга 1)"))
    }

    @Test
    fun `decodeEntities never guesses an unknown or malformed entity`() {
        // Unknown names, bad hex, empty numeric and out-of-range/astral code
        // points stay LITERAL — a decoder never fabricates a character.
        assertEquals("&foo; &#xZZ; &#; &#x;", decodeEntities("&foo; &#xZZ; &#; &#x;"))
        assertEquals("&#x110000; &#0;", decodeEntities("&#x110000; &#0;"))
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
