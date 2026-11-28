package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnicodeGraphemesTest {
    @Test
    fun `production segmenter matches all official Unicode 16 extended grapheme cases`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/recommend/tokenizer/GraphemeBreakTest-16.0.0.txt"))
        var checked = 0
        val failures = ArrayList<String>()
        stream.bufferedReader().useLines { lines ->
            lines.forEachIndexed { lineNumber, line ->
                val specification = line.substringBefore('#').trim()
                if (specification.isNotEmpty()) {
                    val text = StringBuilder()
                    val expected = ArrayList<Int>()
                    for (item in specification.split(Regex("\\s+"))) {
                        when (item) {
                            "÷" -> expected.add(text.length)
                            "×" -> Unit
                            else -> text.appendCodePoint(item.toInt(16))
                        }
                    }
                    val actual = UnicodeGraphemes.boundaries(text.toString())
                    if (!actual.contentEquals(expected.toIntArray())) {
                        failures.add("line ${lineNumber + 1}: expected $expected, actual ${actual.toList()}")
                    }
                    checked++
                }
            }
        }
        assertEquals(1093, checked)
        assertTrue("${failures.size} conformance failures: ${failures.take(5)}", failures.isEmpty())
    }
}
