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
 * this guard pins the rules that are mechanical:
 *
 * 1. every listed surface calls [com.slukhayka.audiobooks.ui.components.EmptyStatePanel]
 *    at least once — reverting one to `EmptyState` or to a hand-rolled column
 *    fails the build;
 * 2. no panel call passes an `icon`-like argument;
 * 3. the component's parameter list is exactly the six pinned slots — so an
 *    `icon: (@Composable () -> Unit)?` slot added later fails by NAME as well as
 *    by compiler, and no `ImageVector`/painter can enter the signature;
 * 4. the component's BODY draws no `Icon(...)` / `PaperInkIcon(...)` glyph.
 *
 * **Named limits** (read, not assumed):
 * - This is a source scan, not a Kotlin parser, and it reads only the six listed
 *   files. A SEVENTH screen hand-rolling its own empty column cannot be told
 *   apart from any other column by this test: the guard's coverage is exactly
 *   the six migrated surfaces plus the shared component itself, and nothing
 *   else.
 * - It does not pin ANCHORING: whether a host hands the panel a height is a
 *   layout property of the call site, invisible to a source scan. That contract
 *   is held by `EmptyStatePanelTest` (centring inside a given height) and by the
 *   render tests for the Library/Ranking surfaces, not by this guard.
 * - A runtime "the panel draws no glyph" assertion would be vacuous on Compose
 *   semantics (an `Icon` is a layout node, not a semantics node with a stable
 *   tag), so the glyph rule is checked at the source, where it is decidable.
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
    fun `the component's parameter list is exactly the allowed slots`() {
        val header = componentHeader(read("ui/components/DesignSystem.kt"))
            ?: error("the EmptyStatePanel declaration moved — this guard cannot read it")

        assertEquals(
            "EmptyStatePanel's parameters are pinned: a slot that is not text, a " +
                "modifier, an optional state description or an action is how an " +
                "icon/painter enters through the back door",
            ALLOWED_PARAMETERS,
            parameterNames(header)
        )
        assertTrue(
            "no parameter of EmptyStatePanel may name an icon/glyph/image slot: $header",
            parameterNames(header).none { name ->
                listOf("icon", "glyph", "image", "painter", "drawable").any { name.contains(it) }
            }
        )
        assertTrue(
            "EmptyStatePanel's signature must not mention image types: $header",
            listOf("ImageVector", "Painter", "ImageBitmap", "Drawable").none { header.contains(it) }
        )
    }

    @Test
    fun `the component's body draws no icon glyph`() {
        val source = read("ui/components/DesignSystem.kt")
        val body = componentBody(source)
            ?: error("the EmptyStatePanel body moved — this guard cannot read it")
        val glyphs = ICON_CALL.findAll(body).map { it.value.trim() }.toList()

        assertTrue(
            "the panel body must be type only: an Icon/PaperInkIcon call is a " +
                "placeholder glyph by another name: $glyphs",
            glyphs.isEmpty()
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
        assertTrue(
            "the nested call is its own entry: $found",
            found.any { arguments -> arguments.trimStart().startsWith("icon = null") }
        )
        // A nested glyph is caught twice — once in the outer call's argument
        // text and once in the nested call's own — which is the safe direction
        // for a guard: it can over-report, never silently miss.
        assertEquals(2, found.count { arguments -> ICON_ARGUMENT.containsMatchIn(arguments) })
    }

    private fun read(relativePath: String): String {
        val file = File(sourceRoot, relativePath)
        check(file.isFile) { "source file not found: $file" }
        return file.readText()
    }

    private companion object {

        /** The exact parameter names the panel may declare, in order. */
        val ALLOWED_PARAMETERS = listOf(
            "title",
            "body",
            "modifier",
            "stateDescription",
            "action",
            "quietAction"
        )

        /**
         * A simple named argument `icon = …` (or a backticked `` `icon` =``).
         * The panel has no such slot, and the compiler would reject it — this
         * is the readable statement of the intent.
         */
        val ICON_ARGUMENT = Regex("""`?icon`?\s*=""")

        /** A rendered glyph call inside a composable body. */
        val ICON_CALL = Regex("""\b(?:PaperInkIcon|Icon)\s*\(""")

        val CALL = Regex("""\bEmptyStatePanel\s*\(""")

        /** `fun EmptyStatePanel(` up to the end of its balanced parameter list. */
        fun componentHeader(source: String): String? {
            val masked = maskCommentsAndStrings(source)
            val match = Regex("""fun\s+EmptyStatePanel\s*\(""").find(masked) ?: return null
            val close = balancedEnd(masked, match.range.last) ?: return null
            return source.substring(match.range.first, close + 1)
        }

        /** The `{ … }` body of `EmptyStatePanel`, as source text. */
        fun componentBody(source: String): String? {
            val masked = maskCommentsAndStrings(source)
            val match = Regex("""fun\s+EmptyStatePanel\s*\(""").find(masked) ?: return null
            val paramsEnd = balancedEnd(masked, match.range.last) ?: return null
            val open = masked.indexOf('{', paramsEnd)
            if (open < 0) return null
            val close = balancedEnd(masked, open) ?: return null
            return source.substring(open + 1, close)
        }

        /** Index of the bracket that closes the one at [open], or null. */
        private fun balancedEnd(code: String, open: Int): Int? {
            var depth = 0
            var index = open
            while (index < code.length) {
                when (code[index]) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
                index++
            }
            return null
        }

        /** Top-level parameter names of a declaration header. */
        fun parameterNames(header: String): List<String> =
            topLevelArguments(header.substringAfter('(').substringBeforeLast(')'))
                .map { argument -> argument.substringBefore(':').trim().removeSurrounding("`") }

        /** The argument text of every `EmptyStatePanel(…)` call, in source order. */
        fun calls(source: String): List<String> = buildList {
            val masked = maskCommentsAndStrings(source)
            var from = 0
            while (true) {
                val match = CALL.find(masked, from) ?: return@buildList
                val open = match.range.last // the '('
                val close = balancedEnd(masked, open)
                    ?: error("unbalanced EmptyStatePanel call near offset ${match.range.first}")
                // Masked text: a commented-out or quoted `icon =` is prose, not
                // an argument.
                add(masked.substring(open + 1, close))
                // Continue INSIDE the arguments: a nested panel must be found
                // and judged on its own rather than swallowed by the outer call.
                from = match.range.first + 1
            }
        }

        /** Splits an argument list on its TOP-LEVEL commas. */
        fun topLevelArguments(arguments: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            arguments.forEachIndexed { index, char ->
                when (char) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    ',' -> if (depth == 0) {
                        parts += arguments.substring(start, index)
                        start = index + 1
                    }
                }
            }
            val tail = arguments.substring(start)
            if (tail.isNotBlank()) parts += tail
            return parts.map(String::trim).filter(String::isNotEmpty)
        }

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
