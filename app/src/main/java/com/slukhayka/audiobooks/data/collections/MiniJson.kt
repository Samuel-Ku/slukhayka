package com.slukhayka.audiobooks.data.collections

/**
 * The tiny recursive-descent JSON parser behind the collections module
 * (pure JVM, no Android org.json stubs — the same convention the source
 * adapters follow).
 *
 * Understands objects (→ [Map]), arrays (→ [List]), strings with standard
 * escapes incl. `\uXXXX` (→ [String]), numbers (→ [Double]) and booleans.
 * A literal `null` is this parser's one blind spot — `parseValue` returns
 * `null` for it exactly as for malformed input, so [parse] rejects a whole
 * document that carries one; [parseLenient] drops `"key": null` pairs first
 * for the callers whose real payloads have them. Returns `null` on any
 * malformed input. Shared by the
 * strict asset decoder ([CollectionJson]) and the live-list sources
 * ([com.slukhayka.audiobooks.data.collections.LiveCollectionSource] implementations such
 * as [OpenLibraryTrendingSource]). Public so the sibling universe module
 * (Spec-25, the curated series-universe assets) shares the ONE parser
 * instead of duplicating a JSON decoder.
 */
object MiniJson {

    /** Parses one JSON document, or `null` when it is not valid JSON. */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue() ?: return null
        if (parser.skipWs() != -1) return null // trailing junk
        return value
    }

    /**
     * [parse] after dropping `"key": null` pairs — the workaround for this
     * decoder's ONE limitation: [Parser.parseValue] returns `null` for both a
     * literal JSON `null` and a parse failure, so a document carrying nulls
     * would otherwise decode to nothing. Real documents do carry them (Open
     * Library work/edition responses are full of `"covers": null`; yt-dlp
     * emits `"acodec": null`), and every caller's field reads treat an ABSENT
     * field exactly like a null one — so the strip loses nothing usable.
     */
    fun parseLenient(text: String): Any? = parse(stripNullFields(text))

    /**
     * Drops `"key": null` pairs from one JSON document. Three regex passes
     * cover pair-with-trailing-comma, pair-with-leading-comma and a lone last
     * pair, repeated until stable (a pair once stripped may leave a new
     * leading-comma neighbour); a `: null` inside a quoted STRING value is
     * never matched, because the value there is quoted rather than a bare
     * `null`. Lived in `YtDlpStreamExtractor` before; the ONE decoder now owns
     * its own workaround and that adapter delegates here.
     */
    fun stripNullFields(json: String): String {
        val nullField = Regex("\"[A-Za-z0-9_-]+\"\\s*:\\s*null")
        val trailingComma = Regex("\"[A-Za-z0-9_-]+\"\\s*:\\s*null\\s*,")
        val leadingComma = Regex(",\\s*\"[A-Za-z0-9_-]+\"\\s*:\\s*null")
        val lone = nullField
        var text = json
        var changed = true
        while (changed) {
            changed = false
            val a = trailingComma.replace(text, "")
            val b = leadingComma.replace(a, "")
            val c = lone.replace(b, "")
            if (c != text) {
                text = c
                changed = true
            }
        }
        return text
    }

    private class Parser(private val input: String) {
        private var pos = 0

        fun skipWs(): Int {
            while (pos < input.length && input[pos].isWhitespace()) pos++
            return if (pos < input.length) input[pos].code else -1
        }

        fun parseValue(): Any? {
            val c = skipWs()
            return when {
                c == -1 -> null
                c == '{'.code -> parseObject()
                c == '['.code -> parseArray()
                c == '"'.code -> parseString()
                c == 't'.code -> if (expect("true")) true else null
                c == 'f'.code -> if (expect("false")) false else null
                c == 'n'.code -> if (expect("null")) null else null
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?>? {
            pos++ // '{'
            val map = mutableMapOf<String, Any?>()
            if (skipWs() == '}'.code) { pos++; return map }
            while (true) {
                if (skipWs() != '"'.code) return null
                val key = parseString() ?: return null
                if (skipWs() != ':'.code) return null
                pos++ // ':'
                val value = parseValue() ?: return null
                map[key] = value
                when (skipWs()) {
                    ','.code -> { pos++; continue }
                    '}'.code -> { pos++; return map }
                    else -> return null
                }
            }
        }

        private fun parseArray(): List<Any?>? {
            pos++ // '['
            val list = mutableListOf<Any?>()
            if (skipWs() == ']'.code) { pos++; return list }
            while (true) {
                val value = parseValue() ?: return null
                list += value
                when (skipWs()) {
                    ','.code -> { pos++; continue }
                    ']'.code -> { pos++; return list }
                    else -> return null
                }
            }
        }

        private fun parseString(): String? {
            pos++ // opening '"'
            val sb = StringBuilder()
            while (pos < input.length) {
                val c = input[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= input.length) return null
                        when (val esc = input[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > input.length) return null
                                val hex = input.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> return null
                        }
                    }
                    c.code < 0x20 -> return null // unescaped control char
                    else -> sb.append(c)
                }
            }
            return null
        }

        private fun parseNumber(): Any? {
            val start = pos
            if (pos < input.length && (input[pos] == '-' || input[pos] == '+')) pos++
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.' || input[pos] == 'e' || input[pos] == 'E' || input[pos] == '-' || input[pos] == '+')) pos++
            if (pos == start) return null
            // Numbers decode to Double so a numeric value can never satisfy
            // the `as? String` field reads of the typed extractors.
            return input.substring(start, pos).toDoubleOrNull() ?: return null
        }

        private fun expect(literal: String): Boolean {
            if (!input.startsWith(literal, pos)) return false
            pos += literal.length
            return true
        }
    }
}
