package com.example.data.collections

/**
 * Spec-16 T1 — strict JSON decoding for the curated collection assets
 * (pure JVM, no Android org.json stubs — the same convention the source
 * adapters follow).
 *
 * The asset format is fixed and curated by the maintainer, so the decoder is
 * deliberately strict: it understands objects with string values, arrays of
 * objects and standard string escapes (including `\uXXXX`), and returns
 * `null` on anything else — a malformed asset never crashes the app, it
 * simply contributes no collection (best-effort load).
 *
 * Expected shape:
 * ```
 * { "id": "nobel", "name": "Нобелівські лауреати",
 *   "sourceNote": "…", "entries": [
 *     { "author": "…", "title": "…", "note": "…" },
 *     { "author": "…" }
 *   ] }
 * ```
 */
object CollectionJson {

    /** Decodes one asset text into a [CollectionList], or `null` when the
     *  text is not a valid collection object (or lacks id/name). */
    fun decode(text: String): CollectionList? {
        return try {
            val parser = Parser(text)
            val value = parser.parseValue() ?: return null
            if (parser.skipWs() != -1) return null // trailing junk
            val obj = value as? Map<*, *> ?: return null
            val id = obj["id"] as? String ?: return null
            val name = obj["name"] as? String ?: return null
            if (id.isBlank() || name.isBlank()) return null
            val sourceNote = obj["sourceNote"] as? String ?: ""
            val entries = when (val raw = obj["entries"]) {
                null -> emptyList()
                is List<*> -> raw.map { entry ->
                    // Strict: any malformed entry invalidates the whole
                    // collection — a curated asset either parses fully or is
                    // absent, so a release review catches data bugs.
                    val map = entry as? Map<*, *> ?: return null
                    val author = map["author"] as? String ?: return null
                    if (author.isBlank()) return null
                    CollectionEntry(
                        author = author,
                        title = map["title"] as? String,
                        note = map["note"] as? String
                    )
                }
                else -> return null
            }
            CollectionList(id, name, sourceNote, entries)
        } catch (e: Exception) {
            null
        }
    }

    /** Tiny recursive-descent JSON parser — objects → [Map], arrays → [List],
     *  strings → [String], numbers/booleans/null kept as-is. */
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
            // the `as? String` field reads below (id/name/author are strings).
            return input.substring(start, pos).toDoubleOrNull() ?: return null
        }

        private fun expect(literal: String) {
            if (!input.startsWith(literal, pos)) throw IllegalArgumentException("expected $literal")
            pos += literal.length
        }
    }
}
