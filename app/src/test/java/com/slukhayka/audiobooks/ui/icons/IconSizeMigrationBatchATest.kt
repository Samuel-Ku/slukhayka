package com.slukhayka.audiobooks.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1776 — batch A of the icon-size migration: the «Слухати» and «Медіатека»
 * screens.
 *
 * [IconSizeTokenGuardTest] keeps the per-file budget shrink-only, but a budget
 * of 0 in its `ALLOWANCE` map is indistinguishable from a file the map never
 * mentioned. This test pins the two migrated screens by NAME, so the question
 * it answers is not "is the whole tree within budget?" but "are THESE screens
 * still free of literal icon sizes?". A literal `18.dp` coming back fails here
 * even if somebody re-adds a budget to the guard.
 *
 * The scan is [IconSizeGuard]'s own (comments, strings and raw strings already
 * masked; `PaperInkIconSizes` tokens already ignored) — this test adds the
 * named-file pin, not a second copy of the scanner.
 */
class IconSizeMigrationBatchATest {

    private val sourceRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks"),
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the Listen and Library screens size every icon from a PaperInkIconSizes role`() {
        // A renamed or deleted screen would otherwise make the pin below pass by
        // scanning nothing at all: the list names files, it must find them.
        val missing = MIGRATED.filterNot { File(sourceRoot, it).isFile }
        assertEquals(
            "These are the two screens #1776 migrated. A rename must update this list, " +
                "not make the pin vacuous. Not found under $sourceRoot: ",
            emptyList<String>(),
            missing,
        )

        val budget = IconSizeGuard.offendersByFile(sourceRoot)
        val regressions = MIGRATED.mapNotNull { file ->
            budget[file]?.let { "$file: $it literal-sized icon call site(s)" }
        }
        assertEquals(
            "#1776 replaced every literal icon size in these screens with a " +
                "PaperInkIconSizes role. A dp literal here is the rhythm drift the scale " +
                "exists to stop. Still literal: ",
            emptyList<String>(),
            regressions,
        )
    }

    private companion object {
        /** The screens this batch migrated, relative to the UI source root. */
        val MIGRATED = listOf(
            "ui/screens/ListenScreen.kt",
            "ui/screens/LibraryScreen.kt",
        )
    }
}
