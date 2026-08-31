package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.ui.screens.unescapeCapturedHtml
import com.slukhayka.audiobooks.ui.screens.sourceBrowserAdCleanupScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `evaluateJavascript` returns the captured DOM as a JSON string literal: `<`
 * is `\u003C`, `>` is `\u003E`, quotes are `\"`, backslashes are `\\`. The
 * sluhay import path must unescape those before the HTML parser sees the page
 * (found on-device during #88: title/author stayed empty while cover/playlist
 * parsed — the `<meta …>` patterns never matched the literal `\u003Cmeta`).
 */
class WebSourceBrowserDecodeTest {

    @Test
    fun `removes adskeeper and late injected ad slots from source pages`() {
        val script = sourceBrowserAdCleanupScript()

        assertTrue("adskeeper selector is missing", "adskeeper" in script)
        assertTrue("late inserts are not observed", "MutationObserver" in script)
        assertTrue("cleanup is not idempotent", "__slukhaykaAdCleanupInstalled" in script)
    }

    @Test
    fun `decodes the JSON-escaped angle brackets back to html`() {
        val input = "\\u003Chtml lang=\\\"uk\\\"\\u003E\\u003Chead\\u003E"
        assertEquals("<html lang=\"uk\"><head>", unescapeCapturedHtml(input))
    }

    @Test
    fun `decodes a full meta tag with a cyrillic title`() {
        val input = "\\u003Cmeta property=\\\"og:title\\\" content=\\\"\\u041f\\u0430\\u0441\\u0430\\u0436\\u0438\\u0440 - \\u0416\\u0430\\u043d-\\u041a\\u0440\\u0456\\u0441\\u0442\\u043e\\u0444 \\u0413\\u0440\\u0430\\u043d\\u0436\\u0435 (\\u0490\\u0440\\u0430\\u043d\\u0436\\u0435) \\u00BB \\u0421\\u043b\\u0443\\u0445\\u0430\\u0439 \\u0431\\u0435\\u0437\\u043a\\u043e\\u0448\\u0442\\u043e\\u0432\\u043d\\u0456 \\u0410\\u0443\\u0434\\u0456\\u043e\\u041a\\u043d\\u0438\\u0433\\u0438\\u2026\\\"\\u003E"
        val decoded = unescapeCapturedHtml(input)
        assertEquals(
            "<meta property=\"og:title\" content=\"Пасажир - Жан-Крістоф Гранже (Ґранже) » Слухай безкоштовні АудіоКниги…\">",
            decoded
        )
    }

    @Test
    fun `decodes the meta row list markup`() {
        val input = "\\u003Cli\\u003E\\u003Cspan\\u003EНазва\\u003C/span\\u003E \\u003Cspan\\u003EПасажир\\u003C/span\\u003E\\u003C/li\\u003E"
        assertEquals(
            "<li><span>Назва</span> <span>Пасажир</span></li>",
            unescapeCapturedHtml(input)
        )
    }

    @Test
    fun `passes plain html through unchanged`() {
        val input = "<html><body>hello</body></html>"
        assertEquals(input, unescapeCapturedHtml(input))
    }

    @Test
    fun `handles escaped backslash and double quote`() {
        assertEquals("a\\b\"c", unescapeCapturedHtml("a\\\\b\\\"c"))
    }

    @Test
    fun `handles newline tab and carriage return escapes`() {
        assertEquals("a\nb\tc\rd", unescapeCapturedHtml("a\\nb\\tc\\rd"))
    }

    @Test
    fun `leaves malformed unicode escape literal`() {
        // `\uZZZZ` is not a valid hex escape — the backslash stays literal.
        assertEquals("\\uZZZZ", unescapeCapturedHtml("\\uZZZZ"))
    }

    @Test
    fun `decodes the exact head captured on device`() {
        // Truncated real capture from #88 (the yandex script tag was inlined).
        val input = "\\u003Chtml lang=\\\"uk\\\"\\u003E\\u003Chead\\u003E\\u003Cscript src=\\\"https://mc.yandex.com/watch/26812653?callback=_ymjsp843512080\\u0026amp;page-url=https%3A%2F%2Fsluhay.com%2Fsvitova-literatura%2F6177-zhan-kristof-granzhe-pasazhir.html\\\"\\u003E"
        val decoded = unescapeCapturedHtml(input)
        assert(decoded.startsWith("<html lang=\"uk\"><head><script src=\"https://mc.yandex.com/watch/26812653?callback=_ymjsp843512080&amp;page-url=https%3A%2F%2Fsluhay.com%2Fsvitova-literatura%2F6177-zhan-kristof-granzhe-pasazhir.html\">"))
    }
}
