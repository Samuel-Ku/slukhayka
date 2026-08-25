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

        val first = GenreIdentity.fromSourceText("  Химерна   проза  ").single()
        val repeated = GenreIdentity.fromSourceText("химерна проза").single()
        val other = GenreIdentity.fromSourceText("Воєнна проза").single()

        assertEquals(first, repeated)
        assertEquals("Химерна проза", first.label)
        assertTrue(first.id.length <= 40)
        assertNotEquals(first.id, other.id)
    }
}
