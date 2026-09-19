package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec-46 T11 (#572) — the book page keeps ONE feedback door.
 *
 * ADR-0033: «Відгуки» is the single entry on the book page; the
 * «Ваші враження» button that used to sit after the chapter list is removed
 * (ADR-0014 «one tool, one place»). The scan is a source scan on purpose —
 * the same guard style [LibraryCanonicalRowGuardTest] uses for the deleted
 * Медіатека row: a door that is merely "unused" still documents a second
 * entry, and the next feature would revive it instead of reaching for the
 * canonical one.
 *
 * The player's own chapter-sheet door (`PlayerScreen`) is a DIFFERENT surface
 * and deliberately keeps `BookFeedbackEntry`, so this guard scans the book
 * page only: `BookDetailScreen.kt` plus the `screens/bookdetail/` package.
 */
class BookDetailFeedbackDoorGuardTest {

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
    fun `the book page never mounts the second feedback door`() {
        val bookPageFiles = buildList {
            add(sourceRoot.resolve("ui/screens/BookDetailScreen.kt"))
            val bookDetailPackage = sourceRoot.resolve("ui/screens/bookdetail")
            if (bookDetailPackage.isDirectory) {
                addAll(bookDetailPackage.walkTopDown().filter { it.isFile && it.extension == "kt" })
            }
        }

        val offenders = bookPageFiles
            .filter { it.exists() && withoutComments(it.readText()).contains(SECOND_DOOR) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }

        assertTrue(
            "$SECOND_DOOR must stay off the book page — «Відгуки» is the single " +
                "feedback entry (ADR-0033); found in: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * Comments are stripped before matching: a sentence explaining WHY the
     * door is gone is documentation, not a door. The call itself can be
     * `BookFeedbackEntry(...)` or `BookFeedbackEntry { ... }` — the name is
     * the whole signal, so the needle carries no punctuation.
     */
    private fun withoutComments(text: String): String =
        text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private companion object {
        const val SECOND_DOOR = "BookFeedback" + "Entry"
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("//[^\n]*")
    }
}
