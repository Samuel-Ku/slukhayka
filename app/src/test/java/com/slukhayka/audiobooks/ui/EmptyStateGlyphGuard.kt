package com.slukhayka.audiobooks.ui

/**
 * #1638 — the «no placeholder glyph in an empty state» rule as ONE shared
 * scan, instead of a hand-rolled substring check per test.
 *
 * The guard answers two questions about a Kotlin source file:
 *
 * 1. how many `EmptyState(` call sites it has (so a guard can never pass by
 *    finding zero), and
 * 2. which of those call sites pass an `icon` — either named (`icon = …`) or
 *    positionally (the first parameter of `EmptyState` is the icon).
 *
 * `iconContent =` is NOT an icon argument: it replaces the glyph with a live
 * indicator (the loading facade) and is legitimate. The argument list is
 * extracted with balanced parentheses and read as the FIRST argument only, so
 * a long `actions` lambda, a nested `stringResource(a, b)` or a later named
 * parameter cannot shift the verdict.
 */
internal object EmptyStateGlyphGuard {

    private const val CALL = "EmptyState("

    /** The argument text of each `EmptyState(` call, in source order. */
    fun calls(source: String): List<String> = buildList {
        var from = 0
        while (true) {
            val start = source.indexOf(CALL, from)
            if (start < 0) return@buildList
            val open = start + CALL.length - 1
            var depth = 0
            var index = open
            var inString = false
            while (index < source.length) {
                val char = source[index]
                when {
                    inString && char == '\\' -> index++ // skip the escaped character
                    char == '"' -> inString = !inString
                    !inString && (char == '(' || char == '{' || char == '[') -> depth++
                    !inString && (char == ')' || char == '}' || char == ']') -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                index++
            }
            require(index < source.length) { "unbalanced EmptyState( call near offset $start" }
            add(source.substring(open + 1, index))
            from = index + 1
        }
    }

    /** The call sites that pass an icon as their first argument (named or positional). */
    fun iconCalls(source: String): List<String> =
        calls(source).filter { arguments ->
            val first = firstArgument(arguments)
            if (first.isEmpty()) return@filter false
            val namedIcon = Regex("""^icon\s*=""").containsMatchIn(first)
            // EmptyState's first parameter is the icon; every other parameter
            // is either named at the call site or the trailing actions lambda
            // (which lives outside the extracted parentheses).
            val positionalIcon = !first.contains('=')
            namedIcon || positionalIcon
        }

    /** The text before the first top-level comma: EmptyState's `icon` slot. */
    private fun firstArgument(arguments: String): String {
        var depth = 0
        var inString = false
        var index = 0
        while (index < arguments.length) {
            val char = arguments[index]
            when {
                inString && char == '\\' -> index++
                char == '"' -> inString = !inString
                !inString && (char == '(' || char == '{' || char == '[') -> depth++
                !inString && (char == ')' || char == '}' || char == ']') -> depth--
                !inString && depth == 0 && char == ',' ->
                    return arguments.substring(0, index).trim()
            }
            index++
        }
        return arguments.trim()
    }
}
