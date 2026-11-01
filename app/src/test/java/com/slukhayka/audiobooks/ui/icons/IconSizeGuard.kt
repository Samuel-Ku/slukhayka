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
 * This object answers exactly two questions about a UI source tree:
 *
 * 1. which `Icon(...)` / `PaperInkIcon(...)` call sites size their glyph with a
 *    literal `dp` value (named `size = …` or `Modifier.size(…)`), and
 * 2. where in the file each one sits, so an allowlist entry can name a call
 *    site rather than a file.
 *
 * Deliberately NOT a Kotlin parser: it locates the call, walks its balanced
 * parentheses, and looks for a literal size inside the argument text. That is
 * enough for the guard's job — a new literal at a new call site — and cheap
 * enough to run on every test pass.
 */
internal object IconSizeGuard {

    /** One literal-sized icon call site. */
    data class Offender(val file: String, val line: Int, val call: String, val literal: String) {
        /** The allowlist key: a file plus the 1-based line of the call. */
        val key: String get() = "$file:$line"
    }

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
        val code = stripComments(file.readText())
        val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
        return calls(code).mapNotNull { (call, argumentText, callOffset) ->
            val literal = LITERAL.find(argumentText) ?: return@mapNotNull null
            val line = code.take(callOffset + literal.range.first).count { it == '\n' } + 1
            Offender(relative, line, call, literal.value.trim())
        }
    }

    /** Pairs each icon call with its balanced argument text and the call's offset. */
    private fun calls(code: String): List<Triple<String, String, Int>> = buildList {
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
            add(Triple(match.groupValues[1], code.substring(open + 1, index), match.range.first))
            from = index + 1
        }
    }

    /**
     * Blanks comments AND string/char literal contents, because neither is a
     * call site: a commented-out `Icon(…, size = 18.dp)` and a string that
     * quotes such a call must both be ignored. Lengths are preserved (spaces,
     * not deletions), so offsets keep mapping to the same text and line numbers
     * counted after this pass stay correct. Quote characters themselves stay, so
     * the scanner can still tell where a literal began.
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        var inString = false
        var inChar = false
        while (i < source.length) {
            val c = source[i]
            when {
                inString -> {
                    when {
                        c == '\\' -> {
                            out.setCharAt(i, ' ')
                            if (i + 1 < source.length) out.setCharAt(i + 1, ' ')
                            i++
                        }
                        c == '"' -> inString = false
                        else -> out.setCharAt(i, ' ')
                    }
                }
                inChar -> {
                    when {
                        c == '\\' -> {
                            out.setCharAt(i, ' ')
                            if (i + 1 < source.length) out.setCharAt(i + 1, ' ')
                            i++
                        }
                        c == '\'' -> inChar = false
                        else -> out.setCharAt(i, ' ')
                    }
                }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') {
                        out.setCharAt(i, ' ')
                        i++
                    }
                    continue
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
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
                    continue
                }
            }
            i++
        }
        return out.toString()
    }
}
