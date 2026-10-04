package com.slukhayka.audiobooks.data.recommend

import com.squareup.moshi.JsonReader
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class UnigramTokenizerContractTest {
    private val fixture = """
        {
          "normalizer":{"type":"Replace","pattern":{"Regex":" {2,}"},"content":" "},
          "pre_tokenizer":{"type":"Metaspace","replacement":"▁","add_prefix_space":true},
          "model":{"type":"Unigram","unk_id":3,"vocab":[
            ["<s>",0],["<pad>",0],["</s>",0],["<unk>",0],
            ["▁",-1],["▁hello",-2],["world",-2],["▁world",-2]
          ]}
        }
    """.trimIndent()

    private fun tokenizer(source: String = fixture) =
        UnigramTokenizer.fromJson(JsonReader.of(Buffer().writeUtf8(source)))

    @Test
    fun `declared metaspace retains a trailing space token`() {
        assertArrayEquals(intArrayOf(5, 4), tokenizer().encode("hello "))
    }
    private fun charsmapTokenizer(): UnigramTokenizer {
        val map = requireNotNull(javaClass.getResourceAsStream("/recommend/tokenizer/e5-precompiled-charsmap.bin"))
            .use { Base64.getEncoder().encodeToString(it.readBytes()) }
        val source = fixture.replace(
            "\"normalizer\":{\"type\":\"Replace\",\"pattern\":{\"Regex\":\" {2,}\"},\"content\":\" \"}",
            "\"normalizer\":{\"type\":\"Sequence\",\"normalizers\":[{\"type\":\"Precompiled\",\"precompiled_charsmap\":\"$map\"},{\"type\":\"Replace\",\"pattern\":{\"Regex\":\" {2,}\"},\"content\":\" \"}]}"
        ).replace("[\"▁world\",-2]", "[\"▁world\",-2],[\"▁Café\",-2]")
        return tokenizer(source)
    }

    @Test
    fun `declared charsmap removes controls and replaces zero width space`() {
        assertArrayEquals(intArrayOf(5, 6, 5), charsmapTokenizer().encode("hello\u0001world\u200Bhello"))
    }

    @Test
    fun `declared charsmap normalizes a short decomposed grapheme as one unit`() {
        assertArrayEquals(intArrayOf(8), charsmapTokenizer().encode("Cafe\u0301"))
    }

    @Test
    fun `raw added token splits normalizer and metaspace segments before encoding`() {
        val added = """"added_tokens":[{"id":8,"content":"<mask>","single_word":false,"lstrip":false,"rstrip":false,"normalized":false,"special":true}],"""
        val source = fixture.replace("\"model\":", "$added \"model\":")
            .replace("[\"▁world\",-2]", "[\"▁world\",-2],[\"<mask>\",0]")
        assertArrayEquals(intArrayOf(5, 8, 7), tokenizer(source).encode("hello<mask>world"))
    }

    @Test
    fun `unsupported byte fallback fails while parsing instead of silently changing ids`() {
        val source = fixture.replace("\"unk_id\":3", "\"unk_id\":3,\"byte_fallback\":true")
        assertThrows(IllegalArgumentException::class.java) { tokenizer(source) }
    }

    @Test
    fun `missing model type cannot silently select a tokenizer algorithm`() {
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer(fixture.replace("\"type\":\"Unigram\",", ""))
        }
    }

    @Test
    fun `unknown id must point into the declared vocabulary`() {
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer(fixture.replace("\"unk_id\":3", "\"unk_id\":99"))
        }
    }

    @Test
    fun `declared tokenizer truncation cannot be silently ignored`() {
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer(fixture.replace("\"model\":", "\"truncation\":{\"max_length\":4},\"model\":"))
        }
    }

    @Test
    fun `null declaration inside a normalizer sequence is rejected`() {
        val source = fixture.replace(
            "\"normalizer\":{\"type\":\"Replace\",\"pattern\":{\"Regex\":\" {2,}\"},\"content\":\" \"}",
            "\"normalizer\":{\"type\":\"Sequence\",\"normalizers\":[null]}"
        )
        assertThrows(IllegalArgumentException::class.java) { tokenizer(source) }
    }

    private fun lattice(pieces: String): UnigramTokenizer = tokenizer("""
        {"pre_tokenizer":{"type":"Metaspace","replacement":"▁"},
         "model":{"type":"Unigram","unk_id":3,"vocab":[
            ["<s>",0],["<pad>",0],["</s>",0],["<unk>",0],$pieces]}}
    """)

    @Test
    fun `equal score keeps the earlier lattice path`() {
        // Independently confirmed with HF 0.22.0 Unigram, not derived from this DP.
        assertArrayEquals(intArrayOf(4, 5, 7), lattice("""
            ["▁",-1],["a",-2],["ab",-2],["bc",-2],["c",-2],["b",-3]
        """).encode("abc"))
    }

    @Test
    fun `multi scalar piece does not suppress a needed unknown scalar edge`() {
        assertArrayEquals(intArrayOf(4, 3, 6), lattice("""
            ["▁",-1],["XY",-2],["YZ",-1]
        """).encode("XYZ"))
    }

    @Test
    fun `unknown penalty uses the minimum vocabulary score minus ten`() {
        assertArrayEquals(intArrayOf(4, 6), lattice("""
            ["▁",-1],["a",-1],["ab",-100]
        """).encode("ab"))
    }

    @Test
    fun `unsupported metaspace options fail before encoding`() {
        for (option in listOf("\"split\":false", "\"prepend_scheme\":\"never\"", "\"add_prefix_space\":false")) {
            assertThrows(IllegalArgumentException::class.java) {
                tokenizer(fixture.replace("\"replacement\":\"▁\",\"add_prefix_space\":true", "\"replacement\":\"▁\",$option"))
            }
        }
    }

    @Test
    fun `unsupported added token matching flags fail before encoding`() {
        for (flag in listOf("normalized", "lstrip", "rstrip", "single_word")) {
            val flags = if (flag == "normalized") "\"normalized\":true" else "\"normalized\":false,\"$flag\":true"
            val added = """"added_tokens":[{"id":8,"content":"<mask>",$flags}],"""
            val source = fixture.replace("\"model\":", "$added \"model\":")
                .replace("[\"▁world\",-2]", "[\"▁world\",-2],[\"<mask>\",0]")
            assertThrows(IllegalArgumentException::class.java) { tokenizer(source) }
        }
    }

    @Test
    fun `corrupt precompiled map fails while loading`() {
        val source = fixture.replace(
            "\"normalizer\":{\"type\":\"Replace\",\"pattern\":{\"Regex\":\" {2,}\"},\"content\":\" \"}",
            "\"normalizer\":{\"type\":\"Precompiled\",\"precompiled_charsmap\":\"AAAAAA==\"}"
        )
        assertThrows(IllegalArgumentException::class.java) { tokenizer(source) }
    }

}
