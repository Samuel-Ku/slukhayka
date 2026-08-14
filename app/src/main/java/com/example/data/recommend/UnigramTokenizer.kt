package com.example.data.recommend

import com.squareup.moshi.JsonReader
import java.io.File
import java.io.InputStream
import okio.Buffer

/**
 * A minimal pure-JVM SentencePiece **Unigram** tokenizer (spec-19 T3). The
 * bundled multilingual-e5-small model uses an XLM-R tokenizer whose
 * tokenizer.json declares a Unigram model — 250k pieces with log-prob
 * scores, a Metaspace pre-tokenizer (`▁` word-boundary marker) and a
 * precompiled NFKC normalizer. BPE does not apply.
 *
 * Tokenization is Viterbi over the piece lattice: for every position the
 * trie of pieces yields the candidate splits, and the highest total log-prob
 * segmentation wins (the standard SentencePiece Unigram algorithm). Unknown
 * characters fall back to [unkId] (3 for XLM-R).
 *
 * The normalizer approximates the tokenizer's precompiled charsmap with
 * Unicode NFKC + whitespace collapsing — the charsmap is itself an NFKC
 * normalization table, so this is a faithful, dependency-free stand-in.
 *
 * Pure JVM (Moshi + Okio only), deterministic, testable without the model.
 * The Android app and the host-side eval script share this class.
 */
class UnigramTokenizer private constructor(
    private val trie: PieceTrie,
    private val unkId: Int
) {

    /** Encodes [text] into piece ids (no special tokens added). */
    fun encode(text: String): IntArray {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return IntArray(0)
        // Metaspace pre-tokenization (faithful to SentencePiece/HF): split
        // on whitespace, prefix every word with the `▁` marker, then Viterbi
        // each word independently — the `▁` never competes across words.
        val sp = "\u2581"
        val words = normalized.split(Regex("\\s+")).map { sp + it }
        val ids = ArrayList<Int>()
        for (word in words) {
            viterbiInto(word, ids)
        }
        return ids.toIntArray()
    }

    /** NFKC + collapse whitespace runs (stands in for the charsmap). */
    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Unigram Viterbi: best[pos] = max total score for meta[0, pos), with
     * backpointers into the piece trie. Unreachable characters are emitted
     * as [unkId].
     */
    /** Appends the Viterbi segmentation of one metaspace word to [out]. */
    private fun viterbiInto(word: String, out: MutableList<Int>) {
        val wordStart = out.size
        val n = word.length
        val negInf = Double.NEGATIVE_INFINITY
        val best = DoubleArray(n + 1) { negInf }
        val bestLen = IntArray(n + 1) // piece length ending at this position
        best[0] = 0.0
        for (pos in 0 until n) {
            if (best[pos] == negInf) continue
            var node = trie.root
            var end = pos
            while (end < n) {
                node = node.children[word[end]] ?: break
                if (node.score != null) {
                    val cand = best[pos] + node.score!!
                    if (cand > best[end + 1]) {
                        best[end + 1] = cand
                        bestLen[end + 1] = end + 1 - pos
                    }
                }
                end++
            }
        }

        // Backtrack; an unreachable tail emits unk per char (progress, no loop).
        var pos = n
        while (pos > 0) {
            val len = bestLen[pos]
            if (len == 0) {
                out.add(unkId)
                pos -= 1
            } else {
                val piece = word.substring(pos - len, pos)
                out.add(trie.pieceId(piece))
                pos -= len
            }
        }
        // best was built forward; backtracking collected pieces in reverse.
        out.subList(wordStart, out.size).reverse()
    }

    companion object {
        /**
         * Parses a HF tokenizer.json stream into a tokenizer. Uses Moshi's
         * [JsonReader] (pure JVM, streams — the vocab is 250k entries).
         * @throws IllegalArgumentException if the model is not Unigram.
         */
        fun fromJson(reader: JsonReader): UnigramTokenizer {
            var unkId = 3
            val trie = PieceTrie()
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
                                }
                                "unk_id" -> unkId = reader.nextInt()
                                "vocab" -> {
                                    // Ids are the vocab array indices — they
                                    // must match the ONNX model's token ids.
                                    reader.beginArray()
                                    var vocabId = 0
                                    while (reader.hasNext()) {
                                        reader.beginArray()
                                        val token = reader.nextString()
                                        val score = reader.nextDouble()
                                        trie.insert(token, score, vocabId)
                                        vocabId++
                                        reader.endArray()
                                    }
                                    reader.endArray()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return UnigramTokenizer(trie, unkId)
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

        fun insert(piece: String, score: Double, vocabId: Int) {
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
