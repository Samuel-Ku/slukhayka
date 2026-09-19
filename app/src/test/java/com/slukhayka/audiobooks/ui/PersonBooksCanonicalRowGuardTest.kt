package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec-46 T05 (#566) — the person page's row contract.
 *
 * The v1.4 audit found the pushed vertical lists each carrying a row dialect
 * of their own; #566 moved the person page's last hold-out onto the canonical
 * `BookRow` and deleted its private `ListItem` twin. The name must never come
 * back, or a second person-row dialect starts growing again.
 *
 * The scan is a source scan on purpose — the same guard style
 * [LibraryCanonicalRowGuardTest] and `HardcodedColorGuardTest` use: a
 * composable that is merely "unused" still documents a layout the app does not
 * ship, and the next feature would revive it instead of reaching for the
 * canonical row.
 */
class PersonBooksCanonicalRowGuardTest {

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
    fun `the person page carries no row dialect of its own`() {
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(OLD_ROW_COMPONENT) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toList()

        assertTrue(
            "$OLD_ROW_COMPONENT must stay deleted — found in: $offenders",
            offenders.isEmpty()
        )
    }

    private companion object {
        const val OLD_ROW_COMPONENT = "PersonWorkRowItem"
    }
}
