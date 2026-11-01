package com.slukhayka.audiobooks.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1772 (unslop wave 1) — no NEW literal icon size may appear in the UI layer.
 *
 * The audit counted 69 literal sizes across 30 UI files while the direction's
 * scale (`PaperInkIconSizes`: Inline 20 · Chrome 22 · Navigation 23 · Header 24
 * · Transport 28 · Play 30) was used 13 times. A rule that is only written down
 * drifts back within a sprint, so this test pins the current set and makes it
 * shrink-only:
 *
 * - a file with MORE literal-sized `Icon(...)`/`PaperInkIcon(...)` call sites
 *   than it has today fails the build;
 * - a file with FEWER fails too, so the migration tickets (#1776 «Слухати» +
 *   «Медіатека», #1778 the rest) must lower the number instead of leaving a
 *   stale allowance behind.
 *
 * **Named limit.** The allowance is per FILE, not per line, because the same
 * files are edited by every migration ticket and by unrelated UI work: a
 * line-keyed list would go stale on a rebase and turn a moved line into a
 * failure. The price is that replacing one literal with another in an
 * already-allowlisted file passes — the guard stops NEW drift, it does not
 * re-open accepted debt. Shrinking the numbers is the tickets' job.
 *
 * Nothing is repainted by this ticket: every snapshot stays byte-identical.
 */
class IconSizeTokenGuardTest {

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
    fun `no file gains a literal icon size`() {
        val budget = IconSizeGuard.offendersByFile(sourceRoot)
        val over = budget.filter { (file, count) -> count > (ALLOWANCE[file] ?: 0) }
        assertTrue(
            "Literal icon sizes are not allowed — name a PaperInkIconSizes role " +
                "instead, or size the call site from a token. Files over budget: " +
                over.entries.joinToString { (file, count) ->
                    "$file: $count (allowed ${ALLOWANCE[file] ?: 0})"
                },
            over.isEmpty()
        )
    }

    @Test
    fun `no file keeps a budget it no longer needs`() {
        val budget = IconSizeGuard.offendersByFile(sourceRoot)
        val stale = ALLOWANCE.filter { (file, allowed) -> (budget[file] ?: 0) < allowed }
        assertTrue(
            "These allowances no longer match the code — a migration removed literal sizes and " +
                "must lower the number in the same commit: " +
                stale.entries.joinToString { (file, allowed) ->
                    "$file: allowed $allowed, present ${budget[file] ?: 0}"
                },
            stale.isEmpty()
        )
    }

    @Test
    fun `the scanner sees a literal call and ignores comments, strings and tokens`() {
        // A raw string cannot be nested inside a raw string, so the fixture is
        // built line by line: the point is the CONTENT the scanner reads.
        val fixture = buildString {
            appendLine("package fixture")
            appendLine()
            appendLine("// Icon(Heart, null, Modifier.size(18.dp)) — a comment, not a call site")
            appendLine("val documented = " + '"' + "Icon(x, size = 16.dp)" + '"')
            appendLine("val js = " + TRIPLE)
            appendLine("  function draw() { return " + '"' + "Icon(y, size = 42.dp)" + '"' + " }")
            appendLine("  // Icon(z, size = 17.dp)")
            appendLine(TRIPLE)
            appendLine()
            appendLine("fun token() {")
            appendLine("    Icon(icon = Heart, contentDescription = null, modifier = Modifier.size(PaperInkIconSizes.Inline))")
            appendLine("}")
            appendLine()
            appendLine("fun literal() {")
            appendLine("    Icon(icon = Heart, contentDescription = null, modifier = Modifier.size(19.dp))")
            appendLine("}")
            appendLine()
            appendLine("fun afterRawString() {")
            appendLine("    PaperInkIcon(icon = Heart, contentDescription = null, size = 21.dp)")
            appendLine("}")
        }
        val file = File.createTempFile("icon-size-guard", ".kt")
        try {
            file.writeText(fixture)
            val found = IconSizeGuard.offendersIn(file, file.parentFile)
            assertEquals(
                "only real call sites count — not comments, not normal strings, not raw strings, " +
                    "and code AFTER a raw string must still be seen: " +
                    found.joinToString { "${it.call} ${it.literal}" },
                listOf("19", "21"),
                found.map { it.literal.filter(Char::isDigit) }.sorted()
            )
        } finally {
            file.delete()
        }
    }

    private companion object {

        /** Kotlin's raw-string delimiter, kept out of the fixture literal. */
        const val TRIPLE = "\"\"\""

        /**
         * Literal-sized icon call sites per file when the guard was introduced
         * (69 across 30 files). Both migration tickets delete entries from this
         * map; nothing raises a number.
         */
        val ALLOWANCE: Map<String, Int> = mapOf(
            "ui/components/AppTabHeader.kt" to 1,
            "ui/components/BookRow.kt" to 3,
            "ui/components/BookmarkDialog.kt" to 1,
            "ui/components/CatalogCoverImage.kt" to 1,
            "ui/components/DesignSystem.kt" to 1,
            "ui/components/PlayerDebugOverlay.kt" to 4,
            "ui/components/PosterCard.kt" to 2,
            "ui/components/SleepTimerSheet.kt" to 1,
            "ui/components/UpdateBanner.kt" to 2,
            "ui/components/paperink/PaperInkCards.kt" to 1,
            "ui/components/paperink/PaperInkChrome.kt" to 3,
            "ui/components/paperink/PaperInkTabHeader.kt" to 1,
            "ui/screens/AchievementPinButton.kt" to 1,
            "ui/screens/BookDetailReviews.kt" to 5,
            "ui/screens/BookDetailScreen.kt" to 1,
            "ui/screens/HomeFeedContent.kt" to 1,
            "ui/screens/HomeScreen.kt" to 4,
            "ui/screens/LibraryImportSheet.kt" to 1,
            "ui/screens/LibraryRatingScreen.kt" to 1,
            "ui/screens/LibraryScreen.kt" to 8,
            "ui/screens/ListenScreen.kt" to 3,
            "ui/screens/PeopleScreen.kt" to 2,
            "ui/screens/PersonBookmarkButton.kt" to 1,
            "ui/screens/PlayerScreen.kt" to 2,
            "ui/screens/StorageDestinationScreen.kt" to 3,
            "ui/screens/WebSourceBrowserScreen.kt" to 5,
            "ui/screens/bookdetail/BookDetailActions.kt" to 1,
            "ui/screens/bookdetail/BookDetailDeleteModals.kt" to 1,
            "ui/screens/bookdetail/BookDetailRows.kt" to 4,
            "ui/screens/collections/PublicCollectionsRail.kt" to 1,
        )
    }
}
