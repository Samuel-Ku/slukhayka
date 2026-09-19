package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec-46 T16 (#577) — the residual-surface contract, as a source scan.
 *
 * Two acceptance criteria are only observable across the whole file, not in
 * one rendered node:
 *
 * 1. «жодних голих Text/Box» — the library «Збережене» tab's two sub-section
 *    empties live inline in a `LazyColumn` inside a ViewModel-driven screen, so
 *    a compose test cannot render them in isolation. The scan asserts the
 *    canonical state is used there instead of the old `Text`/`Column` pair.
 * 2. «Хром рейлів Огляду з ресурсів» — a hardcoded Ukrainian literal compiles
 *    and renders fine in UK until an English run reaches it; the scan fails on
 *    the exact literal, which is the same guard style
 *    `LibraryCanonicalRowGuardTest` and `HardcodedColorGuardTest` use.
 */
class ResidualEmptyStatesSourceGuardTest {

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
    fun `every residual surface renders a canonical empty state`() {
        val surfaces = mapOf(
            "ui/screens/LibraryScreen.kt" to "EmptyStateRow(",
            "ui/screens/PersonBooksScreen.kt" to "EmptyState(",
            "ui/screens/ChannelImportCard.kt" to "EmptyStateRow(",
            "ui/screens/collections/PublicCollectionsRail.kt" to "EmptyStateRow(",
            "ui/screens/DownloadManagerScreen.kt" to "EmptyState(",
            "ui/screens/HomeScreen.kt" to "EmptyState("
        )

        val missing = surfaces.filter { (path, call) -> !read(path).contains(call) }

        assertTrue(
            "these surfaces must render a canonical empty state: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `the library saved tab's sub-section empties are not bare text`() {
        val source = read("ui/screens/LibraryScreen.kt")

        assertTrue(
            "the saved tab's people/bookmarks empties must ride EmptyStateRow, not a bare Text column",
            source.contains("EmptyStateRow(") &&
                !source.contains("text = stringResource(R.string.lib_saved_empty_")
        )
    }

    @Test
    fun `the overview rail chrome carries no hardcoded ukrainian literal`() {
        val offenders = HARDCODED_LITERALS.filter { (path, literal) ->
            read(path).contains(literal)
        }

        assertTrue(
            "rail chrome must come from the resources; hardcoded literals left in: $offenders",
            offenders.isEmpty()
        )
    }

    private fun read(path: String): String {
        val file = File(sourceRoot, path)
        check(file.isFile) { "source file not found: $file" }
        return file.readText()
    }

    private companion object {
        /**
         * path → the exact literal the v1.4 audit found. Paths are relative to
         * the `com/slukhayka/audiobooks` source root.
         */
        val HARDCODED_LITERALS: List<Pair<String, String>> = listOf(
            "ui/screens/HomeScreen.kt" to "\"Рейтинг\"",
            "ui/screens/HomeScreen.kt" to "\"Виконавці\"",
            "ui/screens/HomeScreen.kt" to "\"Автори\"",
            "ui/screens/HomeScreen.kt" to "\"Серії\"",
            "ui/screens/HomeScreen.kt" to "\"Колекції\"",
            "ui/screens/HomeScreen.kt" to "kind.title ==",
            "ui/screens/HomeFeedContent.kt" to "Локальні рекомендації вже працюють приватно",
            "ui/screens/ListenScreen.kt" to "title = \"Продовжити слухати\"",
            // The person page's counts were the last hardcoded plurals.
            "ui/screens/PersonBooksScreen.kt" to "ukPlural("
        )
    }
}
