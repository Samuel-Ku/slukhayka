package com.slukhayka.audiobooks.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1673 — the «Збережене» section headers «Люди»/«Закладки» were bare
 * `Text(style = titleSmall)` and inherited `LocalContentColor`, which the ink
 * theme leaves too dark on its near-black surface (QA on device: practically
 * unreadable). The fix is one explicit role — `MaterialTheme.colorScheme.onSurface`
 * — used by both headers.
 *
 * This class pins the contract in the two places it can be proved without a
 * device: (1) the source keeps the explicit role (a bare Text is the exact
 * regression), and (2) the ink AND paper schemes' `onSurface` clear the 4.5:1
 * text floor on the surfaces the screen draws on.
 */
class LibrarySavedHeaderContrastTest {

    // Unit tests run with the module dir as the working directory, but a
    // source-guard must not depend on that: walk up to the repo root and fall
    // back to the module-relative path.
    private val screen: File = run {
        val relative = "app/src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryScreen.kt"
        val start = File(System.getProperty("user.dir"))
        generateSequence(start) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
            ?: File(start, "src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryScreen.kt")
    }

    private fun helperBody(source: String): String {
        val start = source.indexOf("internal fun LibrarySavedSectionHeader(")
        assertTrue("LibrarySavedSectionHeader helper is gone — re-anchor this guard", start >= 0)
        val nextFunction = source.indexOf("\n@Composable", start + 1)
        return source.substring(start, if (nextFunction > start) nextFunction else source.length)
    }

    @Test
    fun `saved section headers keep an explicit onSurface role`() {
        assertTrue("LibraryScreen.kt not found at ${screen.absolutePath}", screen.isFile)
        val source = screen.readText()
        val helper = helperBody(source)

        assertTrue(
            "the helper must set the role explicitly, not inherit LocalContentColor",
            helper.contains("color = MaterialTheme.colorScheme.onSurface"),
        )
        assertEquals(
            "both header call sites must go through the helper",
            2,
            Regex("LibrarySavedSectionHeader\\(\\s*\\n?\\s*text =").findAll(source).count(),
        )
        for (tag in listOf("library_saved_people_header", "library_saved_bookmarks_header")) {
            assertTrue("«$tag» must be rendered by the helper", helperCallUsesTag(source, tag))
        }
        assertEquals(
            "the saved headers must use the ONE titleSmall in the helper " +
                "(LibraryScreen.kt has other titleSmall Texts the fix must not touch)",
            1,
            Regex("typography\\.titleSmall").findAll(helper).count(),
        )
    }

    private fun helperCallUsesTag(source: String, tag: String): Boolean {
        val block = Regex(
            "LibrarySavedSectionHeader\\([\\s\\S]{0,200}?testTag\\s*=\\s*\"$tag\""
        )
        return block.containsMatchIn(source)
    }

    @Test
    fun `onSurface clears the text floor on the saved screen surfaces`() {
        for ((name, scheme) in listOf(
            "ink" to paperInkDarkScheme(),
            "paper" to paperInkLightScheme(),
        )) {
            for ((surfaceName, surface) in listOf(
                "background" to scheme.background,
                "surface" to scheme.surface,
            )) {
                val ratio = ColorContrast.ratio(scheme.onSurface, surface)
                assertTrue(
                    "$name/$surfaceName: onSurface ratio ${"%.2f".format(java.util.Locale.US, ratio)}" +
                        " must be ≥ ${ColorContrast.TEXT_FLOOR}",
                    ColorContrast.meetsTextFloor(scheme.onSurface, surface),
                )
            }
        }
    }
}
