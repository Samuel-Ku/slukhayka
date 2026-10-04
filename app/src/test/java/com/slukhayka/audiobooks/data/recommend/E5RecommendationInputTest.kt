package com.slukhayka.audiobooks.data.recommend

import com.squareup.moshi.JsonReader
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class E5RecommendationInputTest {
    private val json = """
        {
          "model": {"type":"Unigram", "unk_id":3, "vocab":[
            ["<s>",0.0], ["<pad>",0.0], ["</s>",0.0], ["<unk>",0.0],
            ["▁query:",-1.0], ["▁passage:",-1.0], ["▁book",-1.0]
          ]},
          "post_processor": {
            "type":"TemplateProcessing",
            "single":[{"SpecialToken":{"id":"<s>","type_id":0}},
              {"Sequence":{"id":"A","type_id":0}},
              {"SpecialToken":{"id":"</s>","type_id":0}}],
            "special_tokens":{"<s>":{"ids":[0]}, "</s>":{"ids":[2]}}
          }
        }
    """.trimIndent()

    private fun tokenizer(source: String = json) =
        UnigramTokenizer.fromJson(JsonReader.of(Buffer().writeUtf8(source)))

    @Test fun `model framing follows the declared special token template`() {
        assertArrayEquals(intArrayOf(0, 6, 2), tokenizer().encodeForModel("book"))
        assertArrayEquals(intArrayOf(6), tokenizer().encode("book"))
    }

    @Test fun `empty input retains both declared boundary tokens`() {
        assertArrayEquals(intArrayOf(0, 2), tokenizer().encodeForModel(""))
    }

    @Test fun `truncation reserves the final boundary at the model length limit`() {
        for (pieces in listOf(510, 511, 520)) {
            val encoded = tokenizer().encodeForModel(List(pieces) { "book" }.joinToString(" "))
            assertEquals(512, encoded.size)
            assertEquals(0, encoded.first())
            assertEquals(2, encoded.last())
            assertArrayEquals(IntArray(510) { 6 }, encoded.copyOfRange(1, 511))
        }
    }

    @Test fun `symmetric recommendations use the official query prefix on every input`() {
        assertArrayEquals(intArrayOf(0, 4, 6, 2), E5RecommendationInput.encode("book", tokenizer()))
        assertArrayEquals(intArrayOf(0, 5, 6, 2), E5RecommendationInput.encode("book", tokenizer(), "passage: "))
    }

    @Test fun `vector context binds assets runtime and explicit prefix`() {
        val model = "1".repeat(64)
        val tokenizer = "2".repeat(64)
        val base = E5RecommendationInput.cacheContext(model, tokenizer, "1.21.0")
        assertEquals(384, base.dimension)
        assertEquals(base, E5RecommendationInput.cacheContext(model, tokenizer, "1.21.0", "query: "))
        assertNotEquals(base, E5RecommendationInput.cacheContext("3".repeat(64), tokenizer, "1.21.0"))
        assertNotEquals(base, E5RecommendationInput.cacheContext(model, "4".repeat(64), "1.21.0"))
        assertNotEquals(base, E5RecommendationInput.cacheContext(model, tokenizer, "1.22.0"))
        assertNotEquals(base, E5RecommendationInput.cacheContext(model, tokenizer, "1.21.0", "passage: "))
    }

    @Test fun `missing or unsupported framing fails closed`() {
        val malformed = json.replace("\"TemplateProcessing\"", "\"UnknownTemplate\"")
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer(malformed).encodeForModel("book")
        }
        val missing = json.replace("\"post_processor\"", "\"unused_post_processor\"")
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer(missing).encodeForModel("book")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer().encodeForModel("book", 1)
        }
    }
}
