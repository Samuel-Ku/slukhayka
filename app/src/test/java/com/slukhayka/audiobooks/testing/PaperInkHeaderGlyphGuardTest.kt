package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1623 — a surface that already speaks the «Папір і голос» direction draws its
 * chrome from ONE glyph family.
 *
 * The device audit found the migrated library header still carrying M3
 * `MoreVert`/`Add` and the search fields still carrying M3 `Search`, while the
 * family has had `More`/`Plus`/`Search` since #1534. Replacing them once is a
 * diff; keeping them replaced is a rule — a mixed weight is most visible exactly
 * on the chrome the direction's first screen is judged by.
 *
 * The guard is a disk read on the JVM: no device, no Android runtime. It names
 * the surfaces fixed in #1623 on purpose — the rest of `ui/` is a follow-up
 * (see the issue), and a blanket rule would either fail on legacy screens or
 * force a rename of the whole tree in one PR.
 */
class PaperInkHeaderGlyphGuardTest {

    private val repoRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/; the IDE may use the repo root.
        val cwd = File(System.getProperty("user.dir"))
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "AGENTS.md").isFile && File(it, "app/build.gradle.kts").isFile }
            ?: error("repo root not found from $cwd")
    }

    /** `Icons.Default.MoreVert` / `Icons.Filled.Add` / `...Search` — the M3
     *  glyphs whose values the family already owns. */
    private val banned: Regex by lazy {
        Regex("""Icons\.(?:Default|Filled)\.(?:MoreVert|Add|Search)\b""")
    }

    /**
     * The migrated surfaces this PR closed. Value = the family glyphs each must
     * draw; the key = its path from the repo root.
     */
    private val surfaces: Map<String, List<String>> = mapOf(
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryScreen.kt" to
            listOf("More", "Plus", "Search"),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/HomeScreen.kt" to
            listOf("More", "Search"),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/BookDetailScreen.kt" to
            listOf("More"),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/BookDetailReviews.kt" to
            listOf("More"),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/WebSourceBrowserScreen.kt" to
            listOf("Plus"),
    )

    @Test
    fun noMaterialGlyphInMigratedPaperHeadersAndSearch() {
        val offenders = surfaces.keys.mapNotNull { path ->
            val text = File(repoRoot, path).readText()
            val matches = banned.findAll(text).map { it.value }.toList()
            if (matches.isEmpty()) null else "$path -> $matches"
        }
        assertTrue(
            "migrated paper chrome must not fall back to M3 MoreVert/Add/Search:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** The browser screen's own M3 leftovers: Warning/Close/Link/Refresh/Home
     *  and the AutoMirrored OpenInNew. The family owns Warning→Alert, Close,
     *  Link and Refresh; Home/OpenInNew have no family glyph, so they move to
     *  the Outlined weight instead of staying Filled (§5.4.7). */
    private val browserBanned: Regex by lazy {
        Regex("""Icons\.(?:Default|Filled)\.(?:Warning|Close|Link|Refresh|Home)\b|Icons\.AutoMirrored\.Filled\.OpenInNew\b""")
    }

    private val browserRequired: List<String> = listOf(
        "PaperInkIcons.Alert",
        "PaperInkIcons.Close",
        "PaperInkIcons.Link",
        "PaperInkIcons.Refresh",
        "Icons.Outlined.Home",
        "Icons.AutoMirrored.Outlined.OpenInNew",
    )

    @Test
    fun webSourceBrowserUsesFamilyOrOutlinedGlyphs() {
        val path = "app/src/main/java/com/slukhayka/audiobooks/ui/screens/WebSourceBrowserScreen.kt"
        val text = File(repoRoot, path).readText()
        val offenders = browserBanned.findAll(text).map { it.value }.toList()
        assertTrue(
            "$path must not keep M3 Filled glyphs the family or Outlined weight owns: $offenders",
            offenders.isEmpty()
        )
        val missing = browserRequired.filterNot { text.contains(it) }
        assertTrue(
            "$path is missing the migrated glyphs: " + missing.joinToString(),
            missing.isEmpty()
        )
    }

    @Test
    fun everyMigratedSurfaceDrawsItsFamilyGlyph() {
        val missing = surfaces.flatMap { (path, glyphs) ->
            val text = File(repoRoot, path).readText()
            glyphs.filterNot { text.contains("PaperInkIcons.$it") }.map { "$path -> $it" }
        }
        assertTrue(
            "these migrated surfaces must draw the family glyph, not a text or M3 stand-in:\n" +
                missing.joinToString("\n"),
            missing.isEmpty()
        )
        assertFalse(surfaces.isEmpty())
    }
}
