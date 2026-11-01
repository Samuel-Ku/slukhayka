package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1766 (audit U9/U13) — the six migrated empty states stay on the ONE shared
 * panel, and the panel stays glyph-free.
 *
 * The six surfaces below used five different anatomies before this ticket
 * (decorative icon, amber icon, no icon; two buttons, one, none; pinned to the
 * top). The migration is only worth anything if it cannot quietly erode, so
 * this guard pins the two rules that are mechanical:
 *
 * 1. every listed surface calls [com.slukhayka.audiobooks.ui.components.EmptyStatePanel]
 *    at least once — reverting one to `EmptyState` or to a hand-rolled column
 *    fails the build;
 * 2. no panel call passes an `icon`-like argument, and the component itself has
 *    no `icon` parameter — the absence is stated in type (§4), so an icon slot
 *    must not come back through a back door.
 *
 * **Named limit.** This is a source scan, not a Kotlin parser: an empty state
 * hand-rolled in a SEVENTH screen — or a listed screen's second, non-migrated
 * state — cannot be told apart from any other column, so this guard does not
 * try. It also does not bless an argument the panel does not declare; the
 * compiler does that.
 */
class EmptyStatePanelGuardTest {

    private val sourceRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
    }

    /** The six surfaces #1766 migrates. Every one of them must keep the panel. */
    private val migratedSurfaces = listOf(
        "ui/screens/ListenScreen.kt",
        "ui/screens/LibraryScreen.kt",
        "ui/screens/HomeScreen.kt",
        "ui/screens/FriendsScreen.kt",
        "ui/screens/AchievementsScreen.kt",
        "ui/screens/LibraryRatingScreen.kt"
    )

    @Test
    fun `every migrated surface renders the shared empty-state panel`() {
        val missing = migratedSurfaces.filter { path ->
            calls(read(path)).isEmpty()
        }

        assertTrue(
            "these surfaces no longer call EmptyStatePanel — the #1766 migration " +
                "was reverted or bypassed: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `no panel call passes a placeholder glyph`() {
        val offenders = migratedSurfaces.flatMap { path ->
            calls(read(path))
                .filter { arguments -> ICON_ARGUMENT.containsMatchIn(arguments) }
                .map { arguments -> "$path: EmptyStatePanel(${arguments.trim()})" }
        }

        assertTrue(
            "an empty state is an absence, not a decorated feature — no panel call " +
                "may pass an icon (§4, audit U9): $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the component declares no icon parameter`() {
        val source = read("ui/components/DesignSystem.kt")
        val header = componentHeader(source)
            ?: error("the EmptyStatePanel declaration moved — this guard cannot read it")

        assertTrue(
            "EmptyStatePanel must not grow an icon slot: $header",
            !ICON_ARGUMENT.containsMatchIn(header) && !header.contains("ImageVector")
        )
    }

    @Test
    fun `the scanner reads calls, not prose or strings`() {
        val source = """
            package fixture
            // EmptyStatePanel(title = "commented out")
            val prose = "EmptyStatePanel(icon = PaperInkIcons.Book)"
            fun real() {
                EmptyStatePanel(
                    title = "T", // a call whose second line mentions icon = x
                    body = "B",
                    action = { Button({}) {} }
                )
            }
            fun nested() {
                EmptyStatePanel(title = "outer", action = { EmptyStatePanel(icon = null, title = "i", body = "") })
            }
        """
        val found = calls(source)
        assertEquals("three real calls (one nested), no prose", 3, found.size)
        assertEquals("the nested call is judged on its own", 1, found.count { ICON_ARGUMENT.containsMatchIn(it) })
    }

    private fun read(relativePath: String): String {
        val file = File(sourceRoot, relativePath)
        check(file.isFile) { "source file not found: $file" }
        return file.readText()
    }

    private companion object {

        /**
         * A simple named argument `icon = …` (or a backticked `\`icon\` =`).
         * The panel has no such slot, and the compiler would reject it — this
         * is the readable statement of the intent.
         */
        val ICON_ARGUMENT = Regex("""`?icon`?\s*=""")

        /** `fun EmptyStatePanel(` up to the end of its balanced parameter list. */
        fun componentHeader(source: String): String? {
            val masked = maskCommentsAndStrings(source)
            val match = Regex("""fun\s+EmptyStatePanel\s*\(\s*""").find(masked) ?: return null
            val open = masked.indexOf('(', match.range.first)
            var depth = 0
            var index = open
            while (index < masked.length) {
                when (masked[index]) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> {
                        depth--
                        if (depth == 0) return source.substring(match.range.first, index + 1)
                    }
                }
                index++
            }
            return null
        }

        /** The argument text of every `EmptyStatePanel(…)` call, in source order. */
        fun calls(source: String): List<String> = buildList {
            val masked = maskCommentsAndStrings(source)
            var from = 0
            while (true) {
                val match = CALL.find(masked, from) ?: return@buildList
                val open = match.range.last // the '('
                var depth = 0
                var index = open
                while (index < masked.length) {
                    when (masked[index]) {
                        '(', '{', '[' -> depth++
                        ')', '}', ']' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    index++
                }
                require(index < masked.length) {
                    "unbalanced EmptyStatePanel call near offset ${match.range.first}"
                }
                // Masked text: a commented-out or quoted `icon =` is prose, not
                // an argument.
                add(masked.substring(open + 1, index))
                // Continue INSIDE the arguments: a nested panel must be found
                // and judged on its own rather than swallowed by the outer call.
                from = match.range.first + 1
            }
        }

        val CALL = Regex("""\bEmptyStatePanel\s*\(""")

        /**
         * Blanks comments and string/char literal CONTENTS so a mention in prose
         * or a fixture is not read as a call site. Every character maps to one
         * output character (newlines untouched), so offsets stay valid.
         */
        fun maskCommentsAndStrings(source: String): String {
            val out = StringBuilder(source)
            var i = 0
            while (i < source.length) {
                when {
                    source.startsWith("\"\"\"", i) -> {
                        i += 3
                        while (i < source.length) {
                            if (source.startsWith("\"\"\"", i)) {
                                i += 3
                                break
                            }
                            if (source[i] != '\n') out.setCharAt(i, ' ')
                            i++
                        }
                    }

                    source.startsWith("//", i) -> {
                        while (i < source.length && source[i] != '\n') {
                            out.setCharAt(i, ' ')
                            i++
                        }
                    }

                    source.startsWith("/*", i) -> {
                        i += 2
                        while (i < source.length && !source.startsWith("*/", i)) {
                            if (source[i] != '\n') out.setCharAt(i, ' ')
                            i++
                        }
                        i += 2
                    }

                    source[i] == '"' || source[i] == '\'' -> {
                        val quote = source[i]
                        i++
                        while (i < source.length) {
                            if (source[i] == '\\') {
                                out.setCharAt(i, ' ')
                                if (i + 1 < source.length) {
                                    if (source[i + 1] != '\n') out.setCharAt(i + 1, ' ')
                                    i += 2
                                } else {
                                    i++
                                }
                                continue
                            }
                            if (source[i] == quote) {
                                i++
                                break
                            }
                            if (source[i] != '\n') out.setCharAt(i, ' ')
                            i++
                        }
                    }

                    else -> i++
                }
            }
            return out.toString()
        }
    }
}
