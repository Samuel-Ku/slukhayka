package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.recommend.UnicodeGraphemeTables as G

/** Tokenizer-private extended grapheme boundaries, pinned to Unicode 16.0.0. */
internal object UnicodeGraphemes {
    fun boundaries(text: String): IntArray {
        val result = ArrayList<Int>()
        result.add(0)
        var offset = 0
        var before = G.ANY
        var regionalOdd = false
        var emojiPrefix = false
        var zwjAfterEmoji = false
        var consonantPrefix = false
        var linkerSeen = false
        while (offset < text.length) {
            val scalar = text.codePointAt(offset)
            require(scalar !in 0xD800..0xDFFF) { "Unpaired Unicode surrogate" }
            val after = G.category(scalar)
            val boundary = when {
                before == G.CR && after == G.LF -> false // GB3
                control(before) || control(after) -> true // GB4–5
                before == G.L && (after == G.L || after == G.V || after == G.LV || after == G.LVT) -> false // GB6
                (before == G.LV || before == G.V) && (after == G.V || after == G.T) -> false // GB7
                (before == G.LVT || before == G.T) && after == G.T -> false // GB8
                after == G.EXTEND || after == G.ZWJ -> false // GB9
                after == G.SPACINGMARK || before == G.PREPEND -> false // GB9a–b
                after == G.INCB_CONSONANT && consonantPrefix && linkerSeen -> false // GB9c
                before == G.ZWJ && after == G.EXTENDED_PICTOGRAPHIC && zwjAfterEmoji -> false // GB11
                before == G.REGIONAL_INDICATOR && after == G.REGIONAL_INDICATOR && regionalOdd -> false // GB12–13
                else -> true // GB999
            }
            if (offset > 0 && boundary) result.add(offset)

            val nextZwjAfterEmoji = after == G.ZWJ && emojiPrefix
            if (after == G.EXTENDED_PICTOGRAPHIC) emojiPrefix = true
            else if (after != G.EXTEND) emojiPrefix = false
            zwjAfterEmoji = nextZwjAfterEmoji
            regionalOdd = after == G.REGIONAL_INDICATOR && !regionalOdd
            when {
                after == G.INCB_CONSONANT -> {
                    consonantPrefix = true
                    linkerSeen = false
                }
                G.isInCbLinker(scalar) -> if (consonantPrefix) linkerSeen = true
                !G.isInCbExtend(scalar) -> {
                    consonantPrefix = false
                    linkerSeen = false
                }
            }
            before = after
            offset += Character.charCount(scalar)
        }
        if (text.isNotEmpty()) result.add(text.length)
        return result.toIntArray()
    }

    private fun control(category: Int): Boolean = category == G.CONTROL || category == G.CR || category == G.LF
}
