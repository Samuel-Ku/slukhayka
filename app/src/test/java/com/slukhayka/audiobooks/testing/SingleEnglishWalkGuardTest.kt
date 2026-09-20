package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #986 — the guard that keeps the EN chrome walk singular.
 *
 * #980 collapsed three private walks (#975 index, #978 series, #979
 * collections) onto the shared [EnglishChromeWalk], and #986 moved the last
 * four (Library, Overview, PersonWorks, BookDetail) onto it. The failure this
 * pins is not a wrong walk — it is a fifth slice reaching for its own narrower
 * one and quietly re-opening the `PaneTitle` blind spot #980 had just closed.
 *
 * The scan is a source scan on purpose — the same guard style as
 * [com.slukhayka.audiobooks.ui.CanonicalRowWrappersGuardTest] and
 * [com.slukhayka.audiobooks.ui.ResidualOldNamesGuardTest] — and it matches
 * DECLARATIONS and file-local surfaces, not any mention: the migrated tests
 * legitimately keep their `assertChromeHasNoCyrillic()` wrapper (it now
 * delegates) and their historical comments. Four shapes cover every own walk
 * this repository has grown: the `SemanticsNode` recursion helper, the public
 * `ComposeTestRule` entry point, the private Cyrillic `SemanticsMatcher`, and
 * the inline fetch read beside a local Cyrillic character class — and each
 * needle is assembled from fragments at runtime so this file never matches
 * itself.
 */
class SingleEnglishWalkGuardTest {

    private val testRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        listOf(
            File(System.getProperty("user.dir"), "src/test/java"),
            File(System.getProperty("user.dir"), "app/src/test/java")
        ).firstOrNull { it.isDirectory }
            ?: error("test source root not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the english chrome walk exists nowhere but the shared helper`() {
        val offenders = testRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != SHARED_WALK_FILE }
            .flatMap { file ->
                val text = file.readText()
                RULES
                    .filter { (_, matches) -> matches(text) }
                    .map { (label, _) -> "${file.relativeTo(testRoot).invariantSeparatorsPath}: $label" }
            }
            .toList()

        assertTrue(
            "the EN chrome walk lives only in $SHARED_WALK_FILE — a private one came back: $offenders",
            offenders.isEmpty()
        )
    }

    private companion object {

        /** Built at runtime so the guard file itself never matches. */
        val SHARED_WALK_FILE: String = "English" + "ChromeWalk.kt"

        /** The shared helper's Cyrillic character class, split for the same reason. */
        val CYRILLIC_CLASS: String = "[" + "А-Я" + "а-я" + "ІіЇїЄєҐґ" + "]"

        /** Every shape an own EN walk has taken here, or plausibly could. */
        val RULES: List<Pair<String, (String) -> Boolean>> = listOf(
            // The recursive helper — `collectTexts(node: SemanticsNode)` — that
            // all seven migrated slices used to carry.
            "an own walk over SemanticsNode" to { text ->
                Regex("""\bfun\s+\w+\s*\(\s*\w+\s*:\s*SemanticsNode\b""").containsMatchIn(text)
            },
            // The public entry point a copy of the helper would expose.
            "an own ComposeTestRule walk returning strings" to { text ->
                Regex(
                    """\bfun\s+\w+\s*\([^)]*ComposeTestRule[^)]*\)\s*:\s*List<String>"""
                ).containsMatchIn(text)
            },
            // BookDetail's old form: a private Cyrillic SemanticsMatcher.
            "a private Cyrillic SemanticsMatcher" to { text ->
                Regex(
                    """\bfun\s+\w*""" + "Cyril" + """\w*\s*\([^)]*\)\s*=\s*SemanticsMatcher\b"""
                ).containsMatchIn(text)
            },
            // The inline evade: fetch the roots and read them beside a local
            // Cyrillic character class, without naming a helper at all.
            "an inline semantics fetch beside a Cyrillic character class" to { text ->
                text.contains("fetch" + "SemanticsNode()") && text.contains(CYRILLIC_CLASS)
            }
        )
    }
}
