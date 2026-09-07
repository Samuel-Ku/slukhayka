package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.merge.MergeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM fixture tests for the ADR-0035 / #602 Channel Post Pattern
 * parser. Fixtures are REAL posts harvested 2026-09-07 from the public
 * `t.me/s/stivenkingua` preview (captions elided to the lines that carry
 * identity claims — the «Опис:» bodies add none). Precedent:
 * `CollectionMatcherTest` — no Android, no network, no database.
 */
class TitleNormalizerTest {

    // --- author prefix split (real @stivenkingua posts) --------------------

    @Test
    fun `signed post splits author and title`() {
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг - Острів Дума\n🔊Читає: Сергій Філатов\nОпис: Трудар-мільйонер дивом вижив…",
            "@stivenkingua"
        )

        assertEquals("Стівен Кінг", parsed.author)
        assertEquals("Острів Дума", parsed.title)
        assertEquals("Сергій Філатов", parsed.narrator)
    }

    @Test
    fun `co-author prefix splits as one author claim`() {
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг, Овен Кінг - Сплячі красуні\n🔊Читає: Читач",
            "@stivenkingua"
        )

        assertEquals("Стівен Кінг, Овен Кінг", parsed.author)
        assertEquals("Сплячі красуні", parsed.title)
        // The channel's generic «Читач» is a real claim, kept as-is.
        assertEquals("Читач", parsed.narrator)
    }

    @Test
    fun `post without author prefix keeps author absent`() {
        val parsed = TitleNormalizer.parse("Джералдова гра\n🔊Читає: Alex Nekrasov", "@stivenkingua")

        // The channel brand (Стівен Кінг Аудіокниги) is NEVER substituted
        // for an invented author — a non-King book could appear tomorrow.
        assertNull(parsed.author)
        assertEquals("Джералдова гра", parsed.title)
        assertEquals("Alex Nekrasov", parsed.narrator)
    }

    @Test
    fun `parsed identity agrees with the write-path merge key`() {
        val parsed = TitleNormalizer.parse("Стівен Кінг - Острів Дума", "@stivenkingua")

        assertEquals(
            MergeKey.keyFor("Острів Дума", "Стівен Кінг"),
            MergeKey.keyFor(parsed.title, parsed.author!!)
        )
    }

    // --- narrator lines (observed verb variants) ---------------------------

    @Test
    fun `ozvuchuvav variant is a narrator claim`() {
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг - Газонокосар\nВходить до збірки #Нічна_зміна (13\\20)\n🔊Озвучував: Сергій Філатов",
            "@stivenkingua"
        )

        assertEquals("Сергій Філатов", parsed.narrator)
        assertEquals("Нічна зміна", parsed.series)
        assertEquals(13, parsed.position)
        assertEquals(20, parsed.positionTotal)
    }

    @Test
    fun `multi-narrator claim is kept whole`() {
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг - Вантажівка дядька Отто\n🔊Читає: Сергій Філатов | Макс Шлапак",
            "@stivenkingua"
        )

        assertEquals("Сергій Філатов | Макс Шлапак", parsed.narrator)
    }

    // --- series lines (observed spellings) ---------------------------------

    @Test
    fun `series line with parenthesised backslash position`() {
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг - Діти кукурудзи\nВходить до збірки #Нічна_зміна (16\\20)\n🔊Читає: Сергій Філатов",
            "@stivenkingua"
        )

        assertEquals("Нічна зміна", parsed.series)
        assertEquals(16, parsed.position)
        assertEquals(20, parsed.positionTotal)
    }

    @Test
    fun `series line with book keyword`() {
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг - Вовки Кальї\nСерія: #Темна_вежа (книга 5\\7)\n🔊Читає: BooGaGaрня",
            "@stivenkingua"
        )

        assertEquals("Темна вежа", parsed.series)
        assertEquals(5, parsed.position)
        assertEquals(7, parsed.positionTotal)
    }

    @Test
    fun `series line with bare forward-slash position`() {
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг - Життя Чака\nВходить до збірки #Якщо_кров_тече 2/4\n🔊Читає: Руслан Алексіюк",
            "@stivenkingua"
        )

        assertEquals("Якщо кров тече", parsed.series)
        assertEquals(2, parsed.position)
        assertEquals(4, parsed.positionTotal)
    }

    @Test
    fun `channel typo in series line is tolerated`() {
        // The channel's own «#Все_можливо 4\14)» — opening paren missing.
        val parsed = TitleNormalizer.parse(
            "Стівен Кінг - Смерть Джека Гамільтона\nВходить до збірки #Все_можливо 4\\14)\n🔊Читає: Сергій Філатов",
            "@stivenkingua"
        )

        assertEquals("Все можливо", parsed.series)
        assertEquals(4, parsed.position)
        assertEquals(14, parsed.positionTotal)
    }

    // --- source mark (spec-27 BUG-002 brand) --------------------------------

    @Test
    fun `channel mark behind dash is cut and recorded`() {
        // The real shape from the discussion: «…— АудіоКниги Українською».
        val parsed = TitleNormalizer.parse(
            "Гаррі Поттер і філософський камінь — АудіоКниги Українською",
            "@unknown-channel"
        )

        assertEquals("Гаррі Поттер і філософський камінь", parsed.title)
        assertEquals("АудіоКниги Українською", parsed.sourceMark)
        assertNull(parsed.author)
    }

    @Test
    fun `whole-string mark never blanks the title`() {
        val parsed = TitleNormalizer.parse("Аудіокниги українською", "@unknown-channel")

        assertEquals("Аудіокниги українською", parsed.title)
        assertNull(parsed.sourceMark)
    }

    // --- unknown channel stays raw ------------------------------------------

    @Test
    fun `unparsed title keeps raw text and no claims`() {
        val parsed = TitleNormalizer.parse("Щось незрозуміле (без правил)", "@future-channel")

        assertEquals("Щось незрозуміле (без правил)", parsed.title)
        assertNull(parsed.author)
        assertNull(parsed.narrator)
        assertNull(parsed.series)
        assertNull(parsed.position)
        assertNull(parsed.positionTotal)
    }
}