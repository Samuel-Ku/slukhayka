package com.slukhayka.audiobooks.data.facets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreIdentityTest {
    @Test
    fun `known Ukrainian variants share one stable identity`() {
        val facets = GenreIdentity.fromSourceText(
            "  ФАНТАСТИКА · Наукова   фантастика, sci-fi / Фентезі / фентезі  "
        )

        assertEquals(
            listOf(
                NormalizedGenre("science-fiction", "Фантастика"),
                NormalizedGenre("fantasy", "Фентезі")
            ),
            facets
        )
    }

    @Test
    fun `blank claims stay unknown and unknown identities are bounded deterministic`() {
        assertTrue(GenreIdentity.fromSourceText("  · , /  ").isEmpty())
        assertTrue(GenreIdentity.fromSourceText("4read Каталог").isEmpty())
        assertTrue(GenreIdentity.fromSourceText("Каталог").isEmpty())

        val first = GenreIdentity.fromSourceText("  Химерна   проза  ").single()
        val repeated = GenreIdentity.fromSourceText("химерна проза").single()
        val other = GenreIdentity.fromSourceText("Воєнна проза").single()

        assertEquals(first, repeated)
        assertEquals("Химерна проза", first.label)
        assertTrue(first.id.length <= 40)
        assertNotEquals(first.id, other.id)
    }

    @Test
    fun `canonical input keeps its shared id and derives display only from raw text`() {
        assertEquals(
            NormalizedGenre("shared-genre-id", "Химерна проза"),
            GenreIdentity.fromCanonical("shared-genre-id", "  химерна   проза ")
        )
        assertEquals(
            NormalizedGenre("fantasy", "Фентезі"),
            GenreIdentity.fromCanonical("fantasy", "Фантастика / Фентезі")
        )
        assertEquals(null, GenreIdentity.fromCanonical("catalog", "Каталог"))
    }
}
