package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec-46 T06 (#567) — the search and Work-feed row contract.
 *
 * v1.4 (ADR-0033) settled one vertical-list row, `BookRow`. #567 moved the
 * last two surfaces off their own named wrappers — `GlobalSearchResultCard`
 * and `WorkFeedCard` — and deleted them: their bodies were already the
 * canonical row, so the wrappers only documented a second dialect the app no
 * longer ships.
 *
 * The scan is a source scan on purpose — the same guard style
 * [LibraryCanonicalRowGuardTest] and `HardcodedColorGuardTest` use. It matches
 * DECLARATIONS and imports, not any mention: the call sites legitimately keep
 * a comment naming the wrapper they replaced, but a `fun` by that name coming
 * back means a second row dialect is growing again.
 */
class CanonicalRowWrappersGuardTest {

    private val sourceRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the search and feed row wrappers are gone from main sources`() {
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                DELETED_WRAPPERS
                    .filter { name -> declaresOrImports(text, name) }
                    .map { "${file.relativeTo(sourceRoot).invariantSeparatorsPath}: $it" }
            }
            .toList()

        assertTrue(
            "${DELETED_WRAPPERS.joinToString()} must stay deleted — found in: $offenders",
            offenders.isEmpty()
        )
    }

    private fun declaresOrImports(text: String, name: String): Boolean {
        val declaration = Regex("""\bfun\s+${Regex.escape(name)}\b""")
        val import = Regex("""^import\s+\S*\.${Regex.escape(name)}\s*$""", RegexOption.MULTILINE)
        return declaration.containsMatchIn(text) || import.containsMatchIn(text)
    }

    private companion object {
        // Built at runtime so the guard file itself never matches.
        val DELETED_WRAPPERS = listOf(
            "GlobalSearchResult" + "Card",
            "WorkFeed" + "Card"
        )
    }
}
