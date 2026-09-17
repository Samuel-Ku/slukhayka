package com.slukhayka.audiobooks.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec-54 T10 (#861) — «жодного нового хардкод-кольору».
 *
 * The palette lives in `ui/theme/` so a screen can never carry a colour nobody
 * can find later. There IS accepted debt (two files), and this test PINS it: the
 * counts below may only go DOWN. A new `Color(0x…)` anywhere else fails here
 * instead of quietly forking the palette.
 *
 * `ui/theme/` itself is exempt — that IS the palette.
 */
class HardcodedColorGuardTest {

    /** The accepted debt, file → occurrences. Lower it, never raise it. */
    private val baseline = mapOf(
        // The cover accents are data (a genre → colour table), not a screen's
        // palette; ui/theme/ itself is exempt and not counted here.
        "ui/components/BookCoverImage.kt" to 9
    )

    private val sourceRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
    }

    private fun counts(): Map<String, Int> {
        val pattern = Regex("""Color\(0x""")
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
                relative to pattern.findAll(file.readText()).count()
            }
            .filter { (relative, count) ->
                count > 0 && !relative.startsWith("ui/theme/")
            }
            .toMap()
    }

    @Test
    fun `no NEW hardcoded colour appears outside the palette`() {
        val found = counts()
        val unexpected = found.filterKeys { it !in baseline }
        assertTrue(
            "a hardcoded colour outside ui/theme/: $unexpected — add a role to the palette instead",
            unexpected.isEmpty()
        )
    }

    @Test
    fun `the accepted debt only shrinks`() {
        val found = counts()
        baseline.forEach { (file, limit) ->
            val actual = found[file] ?: 0
            assertTrue(
                "$file has $actual hardcoded colours, baseline allows $limit — lower the baseline",
                actual <= limit
            )
        }
        assertEquals(
            "the palette files are exempt; everything else must be in the baseline",
            baseline.keys,
            found.keys
        )
    }
}
