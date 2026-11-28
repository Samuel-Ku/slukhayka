package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1774 — the source-side half of the four-screen localization guard.
 *
 * [ProfileScreenEnglishChromeTest], [NetworkPrivacyScreenEnglishChromeTest],
 * [RecommendationSettingsScreenEnglishChromeTest] and
 * [WebSourceBrowserEnglishChromeTest] walk rendered EN/PL chrome, but some
 * statuses (import failures, blocked navigation, the repair dialog) only exist
 * after WebView callbacks that a Robolectric run never drives. This scan closes
 * that gap at the source: no composition of the four screens may carry a
 * Cyrillic string literal, whatever state it renders in.
 *
 * Comments are stripped first — a Ukrainian sentence explaining the code is
 * not Ukrainian chrome. The same guard style as
 * [com.slukhayka.audiobooks.ui.BookDetailHardcodedChromeGuardTest].
 */
class LocalizationFourScreensGuardTest {

    private val sourceRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
    }

    private val screens = listOf(
        "ui/screens/ProfileScreen.kt",
        "ui/screens/NetworkPrivacyScreen.kt",
        "ui/screens/RecommendationSettingsScreen.kt",
        "ui/screens/WebSourceBrowserScreen.kt"
    )

    @Test
    fun `the four localized screens have no hardcoded Ukrainian literals`() {
        val offenders = screens.flatMap { relative ->
            val file = sourceRoot.resolve(relative)
            assertTrue("screen composition is missing: $relative", file.isFile)
            cyrillicLiterals(file).map { literal ->
                "${file.relativeTo(sourceRoot).invariantSeparatorsPath}: $literal"
            }
        }

        assertTrue(
            "Ukrainian chrome must come from resources (#1774). " +
                "Hardcoded literals found: $offenders",
            offenders.isEmpty()
        )
    }

    private fun cyrillicLiterals(file: File): List<String> {
        val code = file.readText()
            .replace(BLOCK_COMMENT, "")
            .replace(LINE_COMMENT, "")
        return CYRILLIC_LITERAL.findAll(code).map { it.value }.toList()
    }

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("//[^\n]*")
        val CYRILLIC_LITERAL = Regex("\"[^\"\n]*[А-Яа-яІіЇїЄєҐґ][^\"\n]*\"")
    }
}
