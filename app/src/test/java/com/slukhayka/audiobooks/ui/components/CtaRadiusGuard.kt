package com.slukhayka.audiobooks.ui.components

/**
 * #1775 (unslop U1) — the scanner behind [CtaRadiusTokenGuardTest].
 *
 * The audit's rule 1 (§4 of `docs/audits/2026-11-01-unslop-audit.md`) is "one
 * role, one form": a primary CTA is 24 dp, a secondary action 16 dp, and a pill
 * belongs to a chip, the navigation bar or a play disc — never to an action
 * button. Four radii for «почати/переглянути» proved that a rule in a document
 * does not hold itself; this object finds the two shapes that break it in
 * source: a hand-rolled pill ([PILL_LITERAL]) and a hand-named corner radius
 * ([SHAPE_NAMING]) inside an empty-state action slot, plus a pill on a
 * `Button`/`OutlinedButton`/`TextButton` outside the one chip that is allowed
 * to be one.
 *
 * Deliberately NOT a Kotlin parser: it masks comments and string contents so
 * prose cannot pass for code, then walks balanced parentheses/braces from the
 * token it is looking for. That is enough to say "this slot does not render the
 * shared primitive" or "this button names a pill", and cheap enough to run on
 * every test pass. What it cannot say is what the pixels look like — the
 * layout consequence is held by the goldens, not by this scanner.
 */
internal object CtaRadiusGuard {

    /** One `action = { … }` / `quietAction = { … }` slot body, as masked source. */
    data class Slot(val file: String, val name: String, val body: String)

    /** One source location that breaks the one-role-one-form rule. */
    data class Violation(val file: String, val where: String, val reason: String)

    /** A literal pill. `percent = 50` is the other spelling of `CircleShape`. */
    val PILL_LITERAL = Regex("""\bCircleShape\b|percent\s*=\s*50""")

    /**
     * Source that re-decides the shape instead of inheriting it: a hand-built
     * `RoundedCornerShape` or a bare `.clip(…)`. A CTA slot may contain neither
     * — [EmptyStatePrimaryAction]/[EmptyStateQuietAction] own both.
     */
    val SHAPE_NAMING = Regex("""\bRoundedCornerShape\b|\.clip\s*\(""")

    /** A button-shaped control the pill check inspects. */
    val BUTTON_CALL = Regex("""\b(Button|OutlinedButton|TextButton)\s*\(""")

    val PANEL_CALL = Regex("""\bEmptyStatePanel\s*\(""")
    val PRIMARY_CALL = Regex("""\bEmptyStatePrimaryAction\s*\(""")
    val QUIET_CALL = Regex("""\bEmptyStateQuietAction\s*\(""")
    val SLOT_ARG = Regex("""\b(action|quietAction)\s*=\s*\{""")

    /**
     * The one control in the six surfaces that IS meant to be a pill: the feed
     * toolbar's sort/filter trigger is a chip (`OutlinedButton`, 36/48 dp,
     * outlined), and audit rule 1 gives the pill to chips. Keyed by the tag the
     * surface already publishes, so the exemption cannot drift onto another
     * button by line number.
     */
    const val CHIP_TAG = "feed_sort"

    /**
     * Every `action`/`quietAction` slot body of an [EmptyStatePanel] call in
     * [source].
     *
     * The slots are read from the panel's OWN argument text — not from every
     * `action = {` in the file, because other composables use that parameter
     * name too — and a nested panel call is judged on its own. Duplicates are
     * dropped because an inner call also appears inside its parent's arguments.
     */
    fun slots(file: String, source: String): List<Slot> {
        val masked = maskCommentsAndStrings(source)
        val result = mutableListOf<Slot>()
        for (call in callArguments(masked, PANEL_CALL)) {
            for (match in SLOT_ARG.findAll(call)) {
                val open = match.range.last // the '{'
                val close = balancedEnd(call, open) ?: continue
                result += Slot(file, match.groupValues[1], call.substring(open + 1, close))
            }
        }
        return result.distinctBy { it.name to it.body }
    }

    /** The argument text of every call to [pattern], including nested calls. */
    private fun callArguments(code: String, pattern: Regex): List<String> {
        val result = mutableListOf<String>()
        var from = 0
        while (true) {
            val match = pattern.find(code, from) ?: return result
            val open = match.range.last
            val close = balancedEnd(code, open) ?: return result
            result += code.substring(open + 1, close)
            // Continue INSIDE the arguments so a nested panel is its own entry
            // instead of being swallowed by its parent.
            from = match.range.first + 1
        }
    }

    /**
     * Slot-level violations: a slot that names a shape, hand-rolls a button, or
     * fails to render exactly one shared primitive.
     */
    fun slotViolations(file: String, source: String): List<Violation> =
        slots(file, source).flatMap { slot ->
            val expected = if (slot.name == "action") PRIMARY_CALL else QUIET_CALL
            val expectedName = if (slot.name == "action") {
                "EmptyStatePrimaryAction"
            } else {
                "EmptyStateQuietAction"
            }
            val found = mutableListOf<Violation>()
            PILL_LITERAL.find(slot.body)?.let { match ->
                found += Violation(
                    file,
                    "${slot.name} slot",
                    "names a pill literal `${match.value}` — an action button is a 24 dp " +
                        "CTA, not a CircleShape/percent = 50",
                )
            }
            SHAPE_NAMING.find(slot.body)?.let { match ->
                found += Violation(
                    file,
                    "${slot.name} slot",
                    "re-decides the shape with `${match.value}` — the radius belongs to " +
                        "$expectedName, not to the surface",
                )
            }
            BUTTON_CALL.find(slot.body)?.let { match ->
                found += Violation(
                    file,
                    "${slot.name} slot",
                    "hand-rolls `${match.groupValues[1]}` instead of $expectedName",
                )
            }
            val primitives = expected.findAll(slot.body).count()
            if (primitives != 1) {
                found += Violation(
                    file,
                    "${slot.name} slot",
                    "must render exactly one $expectedName (found $primitives)",
                )
            }
            found
        }

    /**
     * Button-level violations: a `Button`/`OutlinedButton`/`TextButton` whose
     * arguments contain a pill literal, unless it is the chip named in
     * [CHIP_TAG]. String contents are masked for the search, so the chip tag is
     * read from the unmasked argument text on purpose.
     */
    fun buttonViolations(file: String, source: String): List<Violation> {
        val masked = maskCommentsAndStrings(source)
        return BUTTON_CALL.findAll(masked).mapNotNull { match ->
            val open = match.range.last
            val close = balancedEnd(masked, open) ?: return@mapNotNull null
            val arguments = masked.substring(open + 1, close)
            val literal = PILL_LITERAL.find(arguments) ?: return@mapNotNull null
            val original = source.substring(open + 1, close)
            if (original.contains("\"$CHIP_TAG\"")) return@mapNotNull null
            Violation(
                file,
                "${match.groupValues[1]} call",
                "names the pill literal `${literal.value}` — a pill is for chips, the " +
                    "navigation bar and play discs; an action button is a 24 dp CTA",
            )
        }.toList()
    }

    /** The `{ … }` body of `fun [functionName]`, masked, or null when absent. */
    fun functionBody(source: String, functionName: String): String? {
        val masked = maskCommentsAndStrings(source)
        val match = Regex("""\bfun\s+$functionName\s*\(""").find(masked) ?: return null
        val paramsEnd = balancedEnd(masked, match.range.last) ?: return null
        val open = masked.indexOf('{', paramsEnd)
        if (open < 0) return null
        val close = balancedEnd(masked, open) ?: return null
        return masked.substring(open + 1, close)
    }

    /**
     * Blanks comments and string/char literal CONTENTS so a mention in prose or
     * a fixture is not read as source. Every character maps to one output
     * character (newlines untouched), so offsets stay valid for the caller.
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

    /** Index of the bracket that closes the one at [open], or null when unbalanced. */
    fun balancedEnd(code: String, open: Int): Int? {
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
}
