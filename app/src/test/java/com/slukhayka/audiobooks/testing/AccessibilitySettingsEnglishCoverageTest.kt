package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1551 — the download manager and the crash-consent dialog stayed Ukrainian
 * on an English phone: their 25 strings lived only in `values/`, so the
 * resource fallback served the Ukrainian copy (ADR-0033 calls mixed-language
 * UI a consistency defect).
 *
 * This guard reads the two resource files directly, so a missing EN line or a
 * Ukrainian line smuggled into `values-en` fails here — without waiting for a
 * human to open the screens in English.
 */
class AccessibilitySettingsEnglishCoverageTest {

    private val appRoot: File by lazy {
        listOf(File(System.getProperty("user.dir")), File(System.getProperty("user.dir"), "app"))
            .firstOrNull { File(it, "src/main/res").isDirectory }
            ?: error("app root not found from ${System.getProperty("user.dir")}")
    }

    private val uk: File get() = File(appRoot, "src/main/res/values/strings_accessibility_settings.xml")
    private val en: File
        get() = File(appRoot, "src/main/res/values-en/strings_accessibility_settings.xml")

    private fun values(file: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val node = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        node.findAll(file.readText(charset = Charsets.UTF_8)).forEach { match ->
            out[match.groupValues[1]] = match.groupValues[2]
        }
        return out
    }

    private val placeholders = Regex("""%(\d+)\$[sd]""")

    @Test
    fun `the guard reads the real accessibility settings files`() {
        assertTrue(
            "the Ukrainian file was not read from ${uk.path}: a green run would prove nothing",
            values(uk).size >= 100
        )
        assertTrue(
            "the English file was not read from ${en.path}",
            values(en).isNotEmpty()
        )
    }

    @Test
    fun `every accessibility settings string has an english translation`() {
        val missing = values(uk).keys - values(en).keys
        assertEquals(
            "these strings still have no EN value, so an English phone shows Ukrainian:\n" +
                missing.sorted().joinToString("\n"),
            emptySet<String>(),
            missing
        )
    }

    @Test
    fun `english values are not ukrainian`() {
        val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")
        val leaked = values(en)
            .filterValues { cyrillic.containsMatchIn(it) }
            .map { (key, value) -> "$key = $value" }
        assertTrue(
            "Ukrainian text leaked into values-en:\n" + leaked.joinToString("\n"),
            leaked.isEmpty()
        )
    }

    @Test
    fun `format placeholders match the ukrainian source`() {
        val ukValues = values(uk)
        val mismatched = values(en).mapNotNull { (key, value) ->
            val ukValue = ukValues[key] ?: return@mapNotNull null
            val expected = placeholders.findAll(ukValue).map { it.value }.toList()
            val actual = placeholders.findAll(value).map { it.value }.toList()
            if (expected != actual) "$key: EN has $actual, UK has $expected" else null
        }
        assertTrue(
            "placeholder mismatch between EN and UK:\n" + mismatched.joinToString("\n"),
            mismatched.isEmpty()
        )
    }

    @Test
    fun `the two screens that leaked are covered, not just the file`() {
        val ukValues = values(uk)
        val enValues = values(en)
        listOf(
            "crash_reporting_prompt_title",
            "crash_reporting_prompt_body",
            "crash_reporting_allow",
            "crash_reporting_switch_title",
            "download_manager_queue_heading",
            "download_manager_status_downloading",
            "download_manager_remove_message",
            "download_manager_remove_completed_title"
        ).forEach { key ->
            assertTrue("$key missing from values/", key in ukValues)
            assertTrue("$key missing from values-en/", key in enValues)
        }
        assertFalse(
            "the crash consent is still Ukrainian",
            Regex("[А-Яа-яІіЇїЄєҐґ]").containsMatchIn(enValues.getValue("crash_reporting_prompt_body"))
        )
    }
}
