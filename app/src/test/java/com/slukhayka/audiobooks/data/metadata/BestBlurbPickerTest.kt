package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #266 — the cross-source best-blurb rule over a Work's carrier profiles. */
class BestBlurbPickerTest {

    @Test
    fun `longest real blurb wins`() {
        val picked = MetadataAssertions.pickBestBlurb(
            listOf(
                "Короткий блурб.",
                "Набагато довша справжня анотація книги, яка перемагає за правилом найдовшого.",
                "Середній варіант анотації."
            )
        )
        assertEquals(
            "Набагато довша справжня анотація книги, яка перемагає за правилом найдовшого.",
            picked
        )
    }

    @Test
    fun `template fallbacks and blanks never win`() {
        val picked = MetadataAssertions.pickBestBlurb(
            listOf(
                "Аудіокнига з джерела 4read",
                "Аудіокнига з каталогу 4read.org",
                "Аудіокнига з каталогу sound-books.net.",
                "Аудіокнига з каталогу: 4read.org",
                "Аудіокнига з джерела — SomeSource",
                "Аудиокнига из источника: example.org",
                "",
                "   "
            )
        )
        assertNull(picked)
    }

    @Test
    fun `candidates are scrubbed before comparing lengths`() {
        val dirty = "Гарна анотація. Аудіокнига з джерела fake"
        val clean = "Інша чесна анотація книги без сміття всередині."
        val picked = MetadataAssertions.pickBestBlurb(listOf(dirty, clean))
        assertEquals(clean, picked)
    }
}
