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

    /**
     * #702 (T4) — the shelves the sources really claim get ONE shared identity.
     *
     * Every wording below is observed in a captured page: 4read's menu and book
     * pages (`4read-book-7589-2026-09-03.html`), sound-books' categories and
     * lihtar's library. «Дитячі» and «Дитяча література» are the same shelf, so
     * they land on one id — the point of a dictionary.
     */
    @Test
    fun `genres the sources claim share one identity across their wordings`() {
        assertEquals(
            listOf(
                NormalizedGenre("horror", "Жахи"),
                NormalizedGenre("adventure", "Пригоди"),
                NormalizedGenre("biography", "Біографії"),
                NormalizedGenre("self-development", "Саморозвиток"),
                NormalizedGenre("historical-prose", "Історична проза"),
                NormalizedGenre("childrens-literature", "Дитяча література"),
                NormalizedGenre("romance", "Любовні романи")
            ),
            GenreIdentity.fromSourceText(
                "Жахи / Пригоди / Біографії / Саморозвиток / Історична проза / Дитячі / Любовні романи"
            )
        )
        assertEquals(
            "два написання однієї полиці — один id",
            GenreIdentity.fromSourceText("дитячі"),
            GenreIdentity.fromSourceText("Дитяча література")
        )
    }

    /**
     * The honesty guard of the same change: a genre no source claims stays
     * DERIVED, never canonical. «Класика» and «нон-фікшн» are named by the
     * achievements ticket, but no captured source page claims either word, so
     * both keep a hashed identity (ADR-0014, #1053).
     */
    @Test
    fun `a genre no source claims keeps a derived identity`() {
        val unclaimed = GenreIdentity.fromSourceText("Класика / Нон-фікшн")

        assertEquals(listOf("Класика", "Нон-фікшн"), unclaimed.map { it.label })
        assertTrue(
            "вигаданого канонічного id бути не має: ${unclaimed.map { it.id }}",
            unclaimed.none { it.id in GenreIdentity.canonicalIdentities }
        )
    }

    /**
     * #702 (T4) — the migration reads a stored claim with BOTH identities: the
     * one it carries now and the hash it carried before the dictionary knew it.
     * Without the second one a row already in the library could never move.
     */
    @Test
    fun `a stored claim reports its identity now and the hash it carried before`() {
        val claims = GenreIdentity.claimIdentities("  ЖАХИ ")

        assertEquals(1, claims.size)
        assertEquals(NormalizedGenre("horror", "Жахи"), claims.single().genre)
        assertEquals(FacetIdentity.boundedId("genre", "жахи"), claims.single().priorHashedId)
        assertTrue("не-жанр не має заяви: ${GenreIdentity.claimIdentities("Каталог")}", GenreIdentity.claimIdentities("Каталог").isEmpty())
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
