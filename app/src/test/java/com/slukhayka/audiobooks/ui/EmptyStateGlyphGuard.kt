package com.slukhayka.audiobooks.ui

/**
 * #1638 — the «no placeholder glyph in an empty state» rule as ONE shared
 * scan, instead of a hand-rolled substring check per test.
 *
 * The guard answers two questions about a Kotlin source file:
 *
 * 1. how many `EmptyState` call sites it has (so a guard can never pass by
 *    finding zero), and
 * 2. which of those call sites pass an `icon` — either NAMED anywhere in the
 *    argument list (`icon = …`, in any position; Kotlin allows named arguments
 *    in any order) or POSITIONALLY (the first parameter of `EmptyState` is the
 *    icon, and Kotlin only allows positional arguments before the first named
 *    one).
 *
 * `iconContent =` is NOT an icon argument: it replaces the glyph with a live
 * indicator (the loading facade) and is legitimate.
 *
 * Robustness rules this scan keeps (each was a review finding on PR #1639):
 * - comments and string literals are masked before the call is located, so a
 *   `)` inside `// the retry is the action :)` or inside a string cannot close
 *   the argument list early, and a `(` inside a comment cannot break it;
 * - the call itself is `EmptyState` followed by optional whitespace and `(`
 *   (Kotlin allows `EmptyState (…)`);
 * - a NESTED `EmptyState` call inside another call's arguments is located and
 *   judged on its own, not swallowed by the outer scan.
 */
internal object EmptyStateGlyphGuard {

    private val CALL_START = Regex("""\bEmptyState\s*\(""")

    /** The argument text of each `EmptyState` call, in source order. */
    fun calls(source: String): List<String> = buildList {
        val masked = maskCommentsAndStrings(source)
        var from = 0
        while (true) {
            val match = CALL_START.find(masked, from) ?: return@buildList
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
            require(index < masked.length) { "unbalanced EmptyState call near offset ${match.range.first}" }
            add(source.substring(open + 1, index))
            // Continue INSIDE this call's arguments: a nested EmptyState (e.g.
            // in `iconContent = { … }`) must be located and judged on its own,
            // not swallowed by the outer scan.
            from = match.range.first + 1
        }
    }

    /** The call sites that pass an icon anywhere in their argument list. */
    fun iconCalls(source: String): List<String> =
        calls(source).filter { arguments ->
            val parts = topLevelArguments(arguments)
            if (parts.isEmpty()) return@filter false
            // Positional arguments must precede named ones in Kotlin: only the
            // FIRST slot can be the positional icon. `=` inside that slot is NOT
            // what makes it named — `if (state == Error) Icon else null` and
            // `iconFor(mode = 1)` are legal positional expressions.
            val positionalIcon = !isNamedArgument(parts.first())
            positionalIcon || parts.any(::isNamedIcon)
        }

    private val NAMED_ICON = Regex("""^`?icon`?\s*=""")

    private val NAMED_ARGUMENT = Regex("""^`?[A-Za-z_][A-Za-z0-9_]*`?\s*=(?!=)""")

    private fun isNamedIcon(argument: String): Boolean =
        NAMED_ICON.containsMatchIn(maskCommentsAndStrings(argument).trimStart())

    /** True when the FIRST slot is a named argument (`title = …`), not a positional icon. */
    private fun isNamedArgument(argument: String): Boolean =
        NAMED_ARGUMENT.containsMatchIn(maskCommentsAndStrings(argument).trimStart())

    private fun topLevelArguments(arguments: String): List<String> {
        val masked = maskCommentsAndStrings(arguments)
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var index = 0
        while (index < masked.length) {
            when (masked[index]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(arguments.substring(start, index).trim())
                    start = index + 1
                }
            }
            index++
        }
        val tail = arguments.substring(start).trim()
        if (tail.isNotEmpty()) parts.add(tail)
        return parts
    }

    /**
     * Replace string literals (`"…"`, Kotlin raw `"""…"""`), char literals
     * (`'x'`) and comments (`//`, nested `/* … */`) with spaces of the SAME
     * length, so offsets still address the original source. Newlines are
     * preserved. Each of these forms may legally contain `(` or `)`.
     */
    private fun maskCommentsAndStrings(text: String): String {
        val out = StringBuilder(text)
        var index = 0
        while (index < out.length) {
            when {
                out[index] == '"' && index + 2 < out.length &&
                    out[index + 1] == '"' && out[index + 2] == '"' -> {
                    // Kotlin raw string: no escapes, closes on the next `"""`.
                    index = maskSequence(out, index, length = 3, escape = false) { i ->
                        i + 2 < out.length && out[i] == '"' && out[i + 1] == '"' && out[i + 2] == '"'
                    }
                }
                out[index] == '"' -> {
                    index = maskSequence(out, index, length = 1, escape = true) { i ->
                        out[i] == '"'
                    }
                }
                out[index] == '\'' -> {
                    index = maskSequence(out, index, length = 1, escape = true) { i ->
                        out[i] == '\''
                    }
                }
                out[index] == '/' && index + 1 < out.length && out[index + 1] == '/' -> {
                    out.setCharAt(index, ' ')
                    out.setCharAt(index + 1, ' ')
                    index += 2
                    while (index < out.length && out[index] != '\n') {
                        maskChar(out, index)
                        index++
                    }
                }
                out[index] == '/' && index + 1 < out.length && out[index + 1] == '*' -> {
                    // Kotlin block comments NEST: `/* a /* b */ c */`.
                    var depth = 0
                    while (index < out.length) {
                        when {
                            index + 1 < out.length && out[index] == '/' && out[index + 1] == '*' -> {
                                maskChar(out, index)
                                maskChar(out, index + 1)
                                depth++
                                index += 2
                            }
                            index + 1 < out.length && out[index] == '*' && out[index + 1] == '/' -> {
                                maskChar(out, index)
                                maskChar(out, index + 1)
                                depth--
                                index += 2
                                if (depth == 0) break
                            }
                            else -> {
                                maskChar(out, index)
                                index++
                            }
                        }
                    }
                }
                else -> index++
            }
        }
        return out.toString()
    }

    /**
     * Mask a single literal delimited by [isDelimiter]; [length] is the number
     * of opening delimiter characters. Escapes are respected only in the
     * non-raw forms (the caller's `escape` flag): a `"""` string keeps every
     * character literally, while a `"…"`/`'…'` may contain `\"`/`\'`.
     */
    private fun maskSequence(
        out: StringBuilder,
        openIndex: Int,
        length: Int,
        escape: Boolean,
        isDelimiter: (Int) -> Boolean
    ): Int {
        var index = openIndex
        repeat(length) {
            maskChar(out, index)
            index++
        }
        while (index < out.length) {
            if (escape && out[index] == '\\') {
                maskChar(out, index)
                if (index + 1 < out.length) maskChar(out, index + 1)
                index += 2
                continue
            }
            if (isDelimiter(index)) {
                repeat(length) {
                    maskChar(out, index)
                    index++
                }
                return index
            }
            maskChar(out, index)
            index++
        }
        return index
    }

    private fun maskChar(out: StringBuilder, index: Int) {
        if (out[index] != '\n') out.setCharAt(index, ' ')
    }
}
