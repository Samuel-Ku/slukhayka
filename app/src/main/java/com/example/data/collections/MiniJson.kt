package com.example.data.collections

/**
 * The tiny recursive-descent JSON parser behind the collections module
 * (pure JVM, no Android org.json stubs — the same convention the source
 * adapters follow).
 *
 * Understands objects (→ [Map]), arrays (→ [List]), strings with standard
 * escapes incl. `\uXXXX` (→ [String]), numbers (→ [Double]) and
 * booleans/`null`. Returns `null` on any malformed input. Shared by the
 * strict asset decoder ([CollectionJson]) and the live-list sources
 * ([com.example.data.collections.LiveCollectionSource] implementations such
 * as [OpenLibraryTrendingSource]).
 */
internal object MiniJson {

    /** Parses one JSON document, or `null` when it is not valid JSON. */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue() ?: return null
        if (parser.skipWs() != -1) return null // trailing junk
        return value
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
                c == 't'.code -> { expect("true"); true }
                c == 'f'.code -> { expect("false"); false }
                c == 'n'.code -> { expect("null"); null }
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

        private fun expect(literal: String) {
            if (!input.startsWith(literal, pos)) throw IllegalArgumentException("expected $literal")
            pos += literal.length
        }
    }
}
