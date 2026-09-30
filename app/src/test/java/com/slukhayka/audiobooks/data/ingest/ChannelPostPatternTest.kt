package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #830 AC2 — the channel post template, which had NO test at all.
 *
 * A gap analysis on #830 found `ChannelPostPattern` and `ObservedChapterBoundary`
 * referenced once each in `main` and **zero times** in `test`. This covers the
 * pattern half; the boundary half is covered separately.
 *
 * It matters because this is the most expensive logic in the flow — assembling
 * one book out of several posts — and because one rule here is easy to break
 * silently: **the channel's brand is never substituted for an invented
 * author**. A future edit that "helpfully" falls back to the channel name would
 * look reasonable and would put words in a real person's mouth.
 */
class ChannelPostPatternTest {

    // ------------------------------------------------------------------
    // The rule that is easiest to break silently.
    // ------------------------------------------------------------------

    @Test
    fun `an unknown channel never invents an author`() {
        val parsed = TitleNormalizer.parse("Стівен Кінг - Острів Дума", "@unknown-channel")

        assertNull(
            "the brand must never stand in for an author — a non-King book " +
                "could appear in that feed tomorrow",
            parsed.author
        )
    }

    @Test
    fun `an unknown channel leaves the line raw`() {
        // ADR-0035 п. 4: «нерозібраний залишок лишається сирим текстом».
        val parsed = TitleNormalizer.parse("Стівен Кінг - Острів Дума", "@unknown-channel")

        assertEquals("Стівен Кінг - Острів Дума", parsed.title)
    }

    // ------------------------------------------------------------------
    // The known channel's own template.
    // ------------------------------------------------------------------

    @Test
    fun `a known channel splits the author prefix at the dash`() {
        val parsed = TitleNormalizer.parse("Стівен Кінг - Острів Дума", "@stivenkingua")

        assertEquals("Стівен Кінг", parsed.author)
        assertEquals("Острів Дума", parsed.title)
    }

    @Test
    fun `a compound author prefix does not split at the comma`() {
        // «Стівен Кінг, Овен Кінг - …» must keep both names, so prefix order
        // in the pattern is irrelevant.
        val parsed = TitleNormalizer.parse("Стівен Кінг, Овен Кінг - Сплячі красуні", "@stivenkingua")

        assertEquals("Стівен Кінг, Овен Кінг", parsed.author)
        assertEquals("Сплячі красуні", parsed.title)
    }

    @Test
    fun `a prefix with nothing after it never yields an author`() {
        val parsed = TitleNormalizer.parse("Стівен Кінг -", "@stivenkingua")

        assertNull("an empty remainder must not produce an author", parsed.author)
    }

    @Test
    fun `the narrator line is read, emoji and all`() {
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг - Острів Дума\n\uD83D\uDD0AЧитає: Іван Франко",
            "@stivenkingua"
        )

        assertEquals("Іван Франко", parsed.narrator)
        assertEquals("Острів Дума", parsed.title)
    }

    @Test
    fun `the series line yields name and 1-based position`() {
        // The channel's own «4\14)» spelling, underscores as word separators.
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг - Острів Дума\nВходить до збірки #Нічна_зміна (4\\14)",
            "@stivenkingua"
        )

        assertEquals("Нічна зміна", parsed.series)
        assertEquals(4, parsed.position)
        assertEquals(14, parsed.positionTotal)
    }

    @Test
    fun `the brand mark is cut from the end of a title`() {
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг - Острів Дума - аудіокниги українською",
            "@stivenkingua"
        )

        assertEquals("Острів Дума", parsed.title)
        assertEquals("аудіокниги українською", parsed.sourceMark)
    }

    @Test
    fun `an empty caption still returns the raw text as its title`() {
        // The parse must never blank a title: `title ?: raw.trim()` is the
        // floor, so a caller always has something to show.
        val parsed = TitleNormalizer.parse("   ", "@stivenkingua")

        assertEquals("", parsed.title)
    }
}
