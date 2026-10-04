package com.slukhayka.audiobooks.data.recommend

import com.squareup.moshi.JsonReader
import java.io.File
import java.io.InputStream
import okio.Buffer

/**
 * Pure-JVM encoder for the bundled E5 tokenizer's declared HF Unigram contract.
 * The parser accepts supported normalizer, Metaspace and raw added-token
 * declarations explicitly; unsupported configurations fail before encoding.
 *
 * The pinned Precompiled charsmap uses Unicode 16 extended grapheme boundaries,
 * independent of the host's Unicode tables. Scalar-aware Viterbi follows HF
 * 0.22.0 scoring and unknown-token fusion. Android and offline evaluation share
 * this implementation; source pins and licenses are in
 * docs/recommend/tokenizer-provenance.md.
 */
class UnigramTokenizer private constructor(
    private val trie: PieceTrie,
    private val unkId: Int,
    private val modelTemplate: ModelTemplate?,
    private val normalizer: UnigramTextNormalizer,
    private val preTokenizer: UnigramPreTokenizer,
    private val addedTokens: List<AddedToken>
) {

    /** Encodes [text] into piece ids (no special tokens added). */
    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>()
        fun appendPlain(plain: String) {
            for (segment in preTokenizer.split(normalizer.normalize(plain))) viterbiInto(segment, ids)
        }
        var cursor = 0
        while (cursor < text.length) {
            var selected: AddedToken? = null
            var selectedAt = text.length
            for (token in addedTokens) {
                val at = text.indexOf(token.content, cursor)
                if (at >= 0 && (at < selectedAt ||
                        at == selectedAt && token.content.length > (selected?.content?.length ?: 0))) {
                    selected = token
                    selectedAt = at
                }
            }
            val token = selected
            if (token == null) {
                appendPlain(text.substring(cursor))
                break
            }
            if (selectedAt > cursor) appendPlain(text.substring(cursor, selectedAt))
            ids.add(token.id)
            cursor = selectedAt + token.content.length
        }
        return ids.toIntArray()
    }

    /** Applies the declared single-sequence template, retaining EOS after truncation. */
    fun encodeForModel(text: String, maxLength: Int = 512): IntArray {
        require(maxLength >= 2) { "Model input needs both declared boundary tokens" }
        val template = requireNotNull(modelTemplate) { "Tokenizer has no supported model input template" }
        val raw = encode(text)
        val contentLength = minOf(raw.size, maxLength - 2)
        return IntArray(contentLength + 2) { index ->
            when (index) {
                0 -> template.startId
                contentLength + 1 -> template.endId
                else -> raw[index - 1]
            }
        }
    }

    private data class AddedToken(val content: String, val id: Int)

    private data class ModelTemplate(val startToken: String, val startId: Int, val endToken: String, val endId: Int)

    /** Appends one metaspace segment, advancing at Unicode scalar boundaries. */
    private fun viterbiInto(word: String, out: MutableList<Int>) {
        val wordStart = out.size
        val n = word.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val startsAt = IntArray(n + 1) { -1 }
        val ids = IntArray(n + 1)
        best[0] = 0.0
        var pos = 0
        while (pos < n) {
            val scalarLength = Character.charCount(word.codePointAt(pos))
            var hasSingleScalar = false
            var node = trie.root
            var end = pos
            while (end < n) {
                node = node.children[word[end]] ?: break
                node.score?.let { score ->
                    val target = end + 1
                    val candidate = best[pos] + score
                    if (candidate > best[target]) {
                        best[target] = candidate
                        startsAt[target] = pos
                        ids[target] = node.id
                    }
                    if (target - pos == scalarLength) hasSingleScalar = true
                }
                end++
            }
            // A genuine lattice edge keeps the known suffix reachable. Its
            // penalty and missing-single-scalar rule follow HF Unigram.
            if (!hasSingleScalar) {
                val target = pos + scalarLength
                val candidate = best[pos] + trie.minScore - 10.0
                if (candidate > best[target]) {
                    best[target] = candidate
                    startsAt[target] = pos
                    ids[target] = unkId
                }
            }
            pos += scalarLength
        }
        var end = n
        var previousWasUnknown = false
        while (end > 0) {
            check(startsAt[end] >= 0) { "Unreachable tokenizer lattice boundary" }
            val unknown = ids[end] == unkId
            if (!unknown || !previousWasUnknown) out.add(ids[end])
            previousWasUnknown = unknown
            end = startsAt[end]
        }
        out.subList(wordStart, out.size).reverse()
    }

    companion object {
        /**
         * Parses a HF tokenizer.json stream into a tokenizer. Uses Moshi's
         * [JsonReader] (pure JVM, streams — the vocab is 250k entries).
         * @throws IllegalArgumentException if the model is not Unigram.
         */
        fun fromJson(reader: JsonReader): UnigramTokenizer {
            var unkId = -1
            var unigramDeclared = false
            var vocabCount = 0
            var template: ModelTemplate? = null
            val trie = PieceTrie()
            var normalizer = UnigramTextNormalizer.parse(null)
            var preTokenizer = UnigramPreTokenizer.parse(null)
            var addedTokens = emptyList<AddedToken>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "model" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "type" -> {
                                    val type = reader.nextString()
                                    require(type == "Unigram") { "expected a Unigram model, got $type" }
                                    unigramDeclared = true
                                }
                                "unk_id" -> unkId = reader.nextInt()
                                "byte_fallback" -> require(!reader.nextBoolean()) { "Unsupported Unigram byte fallback" }
                                "vocab" -> {
                                    // Ids are the vocab array indices — they
                                    // must match the ONNX model's token ids.
                                    reader.beginArray()
                                    var vocabId = 0
                                    while (reader.hasNext()) {
                                        reader.beginArray()
                                        val token = reader.nextString()
                                        val score = reader.nextDouble()
                                        require(token.isNotEmpty() && score.isFinite()) { "Invalid Unigram vocabulary piece" }
                                        trie.insert(token, score, vocabId)
                                        vocabId++
                                        reader.endArray()
                                    }
                                    reader.endArray()
                                    vocabCount = vocabId
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    "truncation", "padding" -> require(reader.readJsonValue() == null) {
                        "Configured tokenizer padding or truncation is unsupported"
                    }
                    "added_tokens" -> addedTokens = parseAddedTokens(reader.readJsonValue())
                    "normalizer" -> normalizer = UnigramTextNormalizer.parse(reader.readJsonValue())
                    "pre_tokenizer" -> preTokenizer = UnigramPreTokenizer.parse(reader.readJsonValue())
                    "post_processor" -> template = parseTemplate(reader.readJsonValue())
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(unigramDeclared && vocabCount > 0) { "Missing Unigram model declaration or vocabulary" }
            require(unkId in 0 until vocabCount) { "Unknown id is outside the Unigram vocabulary" }
            template?.let {
                require(trie.pieceId(it.startToken) == it.startId && trie.pieceId(it.endToken) == it.endId) {
                    "Tokenizer boundary ids differ from its vocabulary"
                }
            }
            for (token in addedTokens) {
                require(trie.pieceId(token.content) == token.id) { "Added token id differs from its vocabulary" }
            }
            return UnigramTokenizer(trie, unkId, template, normalizer, preTokenizer, addedTokens)
        }

        private fun parseAddedTokens(value: Any?): List<AddedToken> {
            val declarations = value as? List<*> ?: throw IllegalArgumentException("Invalid added token declaration")
            val tokens = declarations.map { declaration ->
                val token = declaration as? Map<*, *> ?: throw IllegalArgumentException("Invalid added token")
                require(token["normalized"] == false) { "Only raw added tokens are supported" }
                for (flag in listOf("single_word", "lstrip", "rstrip")) {
                    require(token[flag] == null || token[flag] == false) { "Unsupported added token flag: $flag" }
                }
                val content = token["content"] as? String ?: throw IllegalArgumentException("Missing added token content")
                require(content.isNotEmpty()) { "Empty added token" }
                val id = token["id"] as? Number ?: throw IllegalArgumentException("Missing added token id")
                require(id.toDouble() == id.toInt().toDouble() && id.toInt() >= 0) { "Invalid added token id" }
                AddedToken(content, id.toInt())
            }
            require(tokens.map { it.content }.distinct().size == tokens.size) { "Repeated added token content" }
            require(tokens.map { it.id }.distinct().size == tokens.size) { "Repeated added token id" }
            return tokens
        }

        private fun parseTemplate(value: Any?): ModelTemplate {
            val processor = value as? Map<*, *> ?: throw IllegalArgumentException("Missing tokenizer template")
            require(processor["type"] == "TemplateProcessing") { "Unsupported tokenizer postprocessor" }
            val single = processor["single"] as? List<*> ?: throw IllegalArgumentException("Missing single template")
            require(single.size == 3) { "Only boundary / sequence A / boundary is supported" }
            val sequence = ((single[1] as? Map<*, *>)?.get("Sequence") as? Map<*, *>)
            require(sequence?.get("id") == "A" && (sequence["type_id"] as? Number)?.toInt() == 0) {
                "Unsupported tokenizer sequence template"
            }
            val tokens = processor["special_tokens"] as? Map<*, *> ?: throw IllegalArgumentException("Missing boundary ids")
            fun boundary(index: Int): Pair<String, Int> {
                val special = ((single[index] as? Map<*, *>)?.get("SpecialToken") as? Map<*, *>)
                    ?: throw IllegalArgumentException("Missing boundary token")
                val token = special["id"] as? String ?: throw IllegalArgumentException("Missing boundary name")
                require((special["type_id"] as? Number)?.toInt() == 0)
                val ids = ((tokens[token] as? Map<*, *>)?.get("ids") as? List<*>)
                    ?: throw IllegalArgumentException("Missing boundary id")
                require(ids.size == 1)
                val id = ids.single() as? Number ?: throw IllegalArgumentException("Invalid boundary id")
                require(id.toDouble() == id.toInt().toDouble() && id.toInt() >= 0)
                return token to id.toInt()
            }
            val start = boundary(0)
            val end = boundary(2)
            return ModelTemplate(start.first, start.second, end.first, end.second)
        }

        /** Parses a tokenizer.json file. */
        fun fromFile(file: File): UnigramTokenizer {
            val buffer = Buffer()
            buffer.write(file.readBytes())
            return fromJson(JsonReader.of(buffer))
        }

        /** Parses a tokenizer.json asset/resource stream. */
        fun fromStream(input: InputStream): UnigramTokenizer {
            val buffer = Buffer()
            buffer.write(input.readBytes())
            return fromJson(JsonReader.of(buffer))
        }
    }

    /** A compact trie of pieces → (score, vocab id). */
    private class PieceTrie {
        class Node {
            val children = HashMap<Char, Node>()
            var score: Double? = null
            var id: Int = -1
        }

        val root = Node()
        var minScore: Double = Double.POSITIVE_INFINITY
            private set

        fun insert(piece: String, score: Double, vocabId: Int) {
            minScore = minOf(minScore, score)
            var node = root
            for (c in piece) node = node.children.getOrPut(c) { Node() }
            node.score = score
            node.id = vocabId
        }

        fun pieceId(piece: String): Int {
            var node = root
            for (c in piece) {
                node = node.children[c] ?: return -1
            }
            return node.id
        }
    }
}
