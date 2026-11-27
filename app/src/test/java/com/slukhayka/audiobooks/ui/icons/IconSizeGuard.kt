package com.slukhayka.audiobooks.ui.icons

import java.io.File

/**
 * #1772 (unslop wave 1) — the scanner behind [IconSizeTokenGuardTest].
 *
 * The audit counted 69 literal icon sizes across 30 UI files against 13 uses of
 * `PaperInkIconSizes`. The direction has ONE scale (Inline 20 · Chrome 22 ·
 * Navigation 23 · Header 24 · Transport 28 · Play 30); a literal `18.dp` next to
 * it is how icon rhythm drifts, one call site at a time.
 *
 * This object answers two questions about a UI source tree: which
 * `Icon(...)` / `PaperInkIcon(...)` calls size their glyph with a literal `dp`
 * value (named `size = …` or `Modifier.size(…)`), and how many such call sites
 * each file has. The guard spends those counts as a per-file budget.
 *
 * Deliberately NOT a Kotlin parser: it locates the call, walks its balanced
 * parentheses, and looks for a literal size inside the argument text. That is
 * enough for the guard's job — a new literal at a new call site — and cheap
 * enough to run on every test pass.
 */
internal object IconSizeGuard {

    /** One literal-sized icon call site. */
    data class Offender(val file: String, val call: String, val literal: String)

    private val CALL = Regex("""\b(PaperInkIcon|Icon)\s*\(""")
    private val LITERAL = Regex("""(?:size\s*=\s*|size\()\s*\d+(?:\.\d+)?\.dp""")

    /** Scans the UI source tree rooted at [sourceRoot] (…/com/slukhayka/audiobooks). */
    fun offenders(sourceRoot: File): List<Offender> {
        val ui = sourceRoot.resolve("ui")
        if (!ui.isDirectory) error("UI source root not found: $ui")
        return ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { file -> offendersIn(file, sourceRoot) }
            .toList()
    }

    /** How many literal-sized icon call sites each file has, files with none omitted. */
    fun offendersByFile(sourceRoot: File): Map<String, Int> =
        offenders(sourceRoot).groupingBy { it.file }.eachCount()

    /** The literal-sized icon call sites of one file. */
    fun offendersIn(file: File, sourceRoot: File): List<Offender> {
        val code = maskNonCode(file.readText())
        val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
        return calls(code).mapNotNull { (call, argumentText) ->
            val literal = LITERAL.find(argumentText) ?: return@mapNotNull null
            Offender(relative, call, literal.value.trim())
        }
    }

    /** Pairs each icon call with its balanced argument text. */
    private fun calls(code: String): List<Pair<String, String>> = buildList {
        var from = 0
        while (true) {
            val match = CALL.find(code, from) ?: return@buildList
            val open = match.range.last
            var depth = 0
            var index = open
            while (index < code.length) {
                when (code[index]) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                index++
            }
            if (depth != 0) return@buildList
            add(match.groupValues[1] to code.substring(open + 1, index))
            from = index + 1
        }
    }

    /**
     * Blanks comments and string/char literal CONTENTS — none of them is a call
     * site: a commented-out `Icon(…, size = 18.dp)`, or a raw `"""…"""` JS
     * snippet that happens to contain `Icon(`, must both be ignored.
     *
     * Three rules keep this honest:
     * - every character of the source maps to exactly one output character, so
     *   offsets stay valid;
     * - NEWLINES are never blanked, so line numbers do not move;
     * - raw strings (`"""…"""`) are handled explicitly. Without that the mask
     *   inverts on a file whose raw string contains a `"`: the quote would be
     *   read as the end of a normal string and the rest of the snippet would be
     *   scanned as code, and vice versa. One of the app's own UI files
     *   (`ui/screens/WebSourceBrowserScreen.kt`) carries JS in raw strings, so
     *   this is not hypothetical.
     */
    private fun maskNonCode(source: String): String {
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
                source[i] == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') {
                        out.setCharAt(i, ' ')
                        i++
                    }
                }
                source[i] == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    out.setCharAt(i, ' ')
                    out.setCharAt(i + 1, ' ')
                    i += 2
                    while (i < source.length) {
                        if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') {
                            out.setCharAt(i, ' ')
                            out.setCharAt(i + 1, ' ')
                            i += 2
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
