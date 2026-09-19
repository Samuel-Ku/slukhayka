package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec-46 T11 (#572) — the book page's chrome lives in string resources.
 *
 * The ticket's fourth acceptance criterion is «UK-хром сторінки книги з
 * ресурсів; EN-прогін чистий». [BookDetailEnglishChromeTest] renders the
 * production composables and walks the EN semantics tree, but the whole
 * `BookDetailScreen` needs a `MainViewModel`, so its inline chrome (the
 * chapter/bookmark tab labels, the ⋮ description, the collection button, the
 * «джерела і слухачі» line, the empty-reviews line) is out of a rendered
 * test's reach. This scan closes that gap at the source, in the same guard
 * style as [LibraryCanonicalRowGuardTest] and `HardcodedColorGuardTest`.
 *
 * Comments are stripped first: a Ukrainian sentence explaining the code is
 * not Ukrainian chrome, and the two must not be confused.
 */
class BookDetailHardcodedChromeGuardTest {

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
    fun `the book page has no hardcoded Ukrainian string literals`() {
        val bookPageFiles = buildList {
            add(sourceRoot.resolve("ui/screens/BookDetailScreen.kt"))
            add(sourceRoot.resolve("ui/screens/BookDetailReviews.kt"))
            add(sourceRoot.resolve("ui/screens/BookDetailPresentation.kt"))
            val bookDetailPackage = sourceRoot.resolve("ui/screens/bookdetail")
            if (bookDetailPackage.isDirectory) {
                addAll(bookDetailPackage.walkTopDown().filter { it.isFile && it.extension == "kt" })
            }
        }.filter { it.exists() }

        val offenders = bookPageFiles.flatMap { file ->
            cyrillicLiterals(file).map { literal ->
                "${file.relativeTo(sourceRoot).invariantSeparatorsPath}: $literal"
            }
        }

        assertTrue(
            "Ukrainian chrome must come from resources (spec-46 T11). " +
                "Hardcoded literals found: $offenders",
            offenders.isEmpty()
        )
    }

    private fun cyrillicLiterals(file: File): List<String> {
        val code = file.readText()
            .replace(BLOCK_COMMENT, "")
            .replace(LINE_COMMENT, "")
        return CYRILLIC_LITERAL.findAll(code).map { it.value }.toList()
    }

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("//[^\n]*")
        val CYRILLIC_LITERAL = Regex("\"[^\"\n]*[А-Яа-яІіЇїЄєҐґ][^\"\n]*\"")
    }
}
