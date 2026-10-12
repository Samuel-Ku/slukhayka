package com.slukhayka.audiobooks.data.recommend

import okio.ByteString.Companion.decodeBase64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

/** Executes only explicitly supported normalizer declarations. */
internal fun interface UnigramTextNormalizer {
    fun normalize(text: String): String

    companion object {
        fun parse(value: Any?): UnigramTextNormalizer {
            if (value == null) return UnigramTextNormalizer { it }
            val config = value as? Map<*, *> ?: throw IllegalArgumentException("Invalid tokenizer normalizer")
            return when (config["type"]) {
                "Sequence" -> {
                    val declarations = config["normalizers"] as? List<*>
                        ?: throw IllegalArgumentException("Missing normalizer sequence")
                    val chain = declarations.map { parse(requireNotNull(it) { "Invalid null normalizer declaration" }) }
                    UnigramTextNormalizer { text -> chain.fold(text) { result, normalizer -> normalizer.normalize(result) } }
                }
                "Precompiled" -> {
                    val encoded = config["precompiled_charsmap"] as? String
                        ?: throw IllegalArgumentException("Missing precompiled charsmap")
                    val bytes = encoded.decodeBase64()?.toByteArray()
                        ?: throw IllegalArgumentException("Invalid precompiled charsmap encoding")
                    val map = PrecompiledCharsMap(bytes)
                    UnigramTextNormalizer { map.normalize(it) }
                }
                "NFKC" -> UnigramTextNormalizer {
                    java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKC)
                }
                "Replace" -> {
                    val pattern = config["pattern"] as? Map<*, *>
                    require(pattern?.get("Regex") == " {2,}" && pattern.size == 1 && config["content"] == " ") {
                        "Unsupported tokenizer replacement"
                    }
                    UnigramTextNormalizer { it.replace(Regex(" {2,}"), " ") }
                }
                else -> throw IllegalArgumentException("Unsupported tokenizer normalizer")
            }
        }
    }
}

/** Darts binary rewrite map, using the pinned HF shortest-prefix contract. */
private class PrecompiledCharsMap(bytes: ByteArray) {
    private val units: IntArray
    private val pool: ByteArray
    private val replacements = ConcurrentHashMap<Int, String>()

    init {
        require(bytes.size >= 9) { "Truncated precompiled charsmap" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val trieBytes = input.int
        require(trieBytes >= 4 && trieBytes % 4 == 0 && trieBytes <= bytes.size - 5) {
            "Invalid precompiled trie size"
        }
        units = IntArray(trieBytes / 4) { input.int }
        pool = ByteArray(input.remaining()).also { input.get(it) }
        require(pool.last() == 0.toByte()) { "Unterminated precompiled replacement pool" }
        decode(pool) // Reject a malformed pool before any text is processed.
        require(offset(units[0]) in units.indices) { "Invalid precompiled root" }
    }

    fun normalize(text: String): String {
        val boundaries = UnicodeGraphemes.boundaries(text)
        return buildString(text.length) {
            for (index in 1 until boundaries.size) {
                val grapheme = text.substring(boundaries[index - 1], boundaries[index])
                val utf8 = grapheme.toByteArray(Charsets.UTF_8)
                val whole = if (utf8.size < 6) transform(utf8) else null
                if (whole != null) {
                    append(whole)
                } else {
                    var pos = 0
                    while (pos < grapheme.length) {
                        val length = Character.charCount(grapheme.codePointAt(pos))
                        val scalar = grapheme.substring(pos, pos + length)
                        append(transform(scalar.toByteArray(Charsets.UTF_8)) ?: scalar)
                        pos += length
                    }
                }
            }
        }
    }

    private fun transform(key: ByteArray): String? {
        var node = offset(units[0])
        for (byte in key) {
            val label = byte.toInt() and 0xFF
            if (label == 0) break
            node = node xor label
            require(node in units.indices) { "Invalid precompiled trie transition" }
            val unit = units[node]
            if (unit and (Int.MIN_VALUE or 0xFF) != label) return null
            node = node xor offset(unit)
            require(node in units.indices) { "Invalid precompiled trie offset" }
            if (unit and 0x100 != 0) {
                val value = units[node] and Int.MAX_VALUE
                require(value in pool.indices) { "Invalid precompiled replacement offset" }
                return replacements.computeIfAbsent(value) { start ->
                    var end = start
                    while (end < pool.size && pool[end] != 0.toByte()) end++
                    require(end < pool.size) { "Unterminated precompiled replacement" }
                    decode(pool.copyOfRange(start, end))
                }
            }
        }
        return null
    }

    private fun offset(unit: Int): Int = (unit ushr 10) shl ((unit and 0x200) ushr 6)

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("Invalid UTF-8 in precompiled replacement", error)
    }
}
