package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec-46 T08 (#569) — the Медіатека's row contract.
 *
 * The v1.4 audit found the library carrying its OWN row component (the sixth
 * row dialect of S3). #569 moved its only remaining consumer onto the canonical
 * `BookRow` and deleted the component: the name must never come back, or a
 * second library row dialect starts growing again.
 *
 * The scan is a source scan on purpose — the same guard style
 * `HardcodedColorGuardTest` uses. A composite that is merely "unused" still
 * documents a layout the app does not ship, and the next feature would revive
 * it instead of reaching for the canonical row.
 */
class LibraryCanonicalRowGuardTest {

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
    fun `the old library row component is gone from main sources`() {
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
        const val OLD_ROW_COMPONENT = "LibraryBookRow" + "Content"
    }
}
