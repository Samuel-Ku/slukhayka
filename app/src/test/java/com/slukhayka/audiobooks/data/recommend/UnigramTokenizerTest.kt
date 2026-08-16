package com.slukhayka.audiobooks.data.recommend

import com.squareup.moshi.JsonReader
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [UnigramTokenizer] (spec-19 T3). Uses a tiny inline
 * Unigram fixture (a mini XLM-R-shaped vocab) — never the real model, so
 * the suite stays fast and the model asset is not required.
 */
class UnigramTokenizerTest {

    /** A tiny SentencePiece-style Unigram vocab with the real algorithm's shape. */
    private val miniJson = """
        {
          "model": {
            "type": "Unigram",
            "unk_id": 3,
            "vocab": [
              ["<s>", 0.0], ["<pad>", 0.0], ["</s>", 0.0], ["<unk>", 0.0],
              ["▁", -1.0],
              ["▁ко", -2.0], ["▁кобзар", -3.0], ["кобзар", -4.0],
              ["▁том", -2.5], ["том", -3.0],
              ["▁один", -2.0], ["один", -3.0],
              ["а", -2.0], ["р", -2.0], ["і", -2.0], ["▁і", -2.0],
              ["▁лісова", -3.0], ["▁пісня", -3.0], ["▁частина", -3.0]
            ]
          }
        }
    """.trimIndent()

    private fun tokenizer(): UnigramTokenizer {
        val buffer = Buffer()
        buffer.writeUtf8(miniJson)
        return UnigramTokenizer.fromJson(JsonReader.of(buffer))
    }

    @Test
    fun `encodes a known word to its vocab ids`() {
        val ids = tokenizer().encode("кобзар")
        // Metaspace: "▁кобзар" is a single piece → its vocab id.
        assertTrue(ids.isNotEmpty())
        assertArrayEquals(intArrayOf(6), ids)
    }

    @Test
    fun `whitespace becomes the word-boundary marker`() {
        val ids = tokenizer().encode("кобзар том")
        // ▁кобзар (6) then ▁том (8) — a space between words is a new ▁ piece.
        assertArrayEquals(intArrayOf(6, 8), ids)
    }

    @Test
    fun `unknown characters fall back to the unk id`() {
        val ids = tokenizer().encode("кобзар ф")
        // "ф" is not in the vocab: the word "▁ф" splits as ▁ (4) + unk (3).
        assertArrayEquals(intArrayOf(6, 4, 3), ids)
    }

    @Test
    fun `nfkc normalization folds compatible characters`() {
        // NBSP (U+00A0) folds to a plain space under NFKC, so the word
        // boundary appears exactly as for a regular space.
        val ids = tokenizer().encode("кобзар\u00A0і")
        assertArrayEquals(intArrayOf(6, 15), ids)
    }

    @Test
    fun `whitespace runs collapse to a single word boundary`() {
        val ids = tokenizer().encode("кобзар     і")
        assertArrayEquals(intArrayOf(6, 15), ids)
    }

    @Test
    fun `empty and blank inputs encode to nothing`() {
        assertTrue(tokenizer().encode("").isEmpty())
        assertTrue(tokenizer().encode("   ").isEmpty())
    }

    @Test
    fun `viterbi picks the higher-score longer piece over character pieces`() {
        // "кобзар" could split as ▁ко+б+за+... but ▁кобзар carries the
        // best total score, so the single piece wins.
        val ids = tokenizer().encode("кобзар")
        assertArrayEquals(intArrayOf(6), ids)
    }
}
