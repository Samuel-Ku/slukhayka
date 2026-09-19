package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.authors.AuthorIdentity
import com.slukhayka.audiobooks.data.db.PersonRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #955 — the role decides which identity a person page reads.
 *
 * An AUTHOR needs the id `work_facets.canonicalAuthorId` carries, so
 * `worksForAuthor` / `ownedWorkIdsForAuthor` can mark the listener's own
 * Works. A NARRATOR never has one — the editions carry only the written name
 * — and must keep the narrator route.
 */
class CatalogPersonCanonicalAuthorTest {

    @Test
    fun `an author without a stored id derives the same id the work index writes`() {
        val person = CatalogPerson(
            name = "Айя Нея",
            path = "/xfsearch/avtor/Айя Нея/",
            bookCount = 3,
            role = PersonRole.AUTHOR
        )

        // The exact value the device database carries in
        // `work_facets.canonicalAuthorId` for both owned Works.
        assertEquals("author-4ba6bdc52ed5f262", person.canonicalAuthorId)
        assertEquals(AuthorIdentity.fromWorkName("Айя Нея").id, person.canonicalAuthorId)
    }

    @Test
    fun `a narrator never gets a canonical author id, even when one is handed in`() {
        val person = CatalogPerson(
            name = "Айя Нея",
            path = "/xfsearch/chitaet/Айя Нея/",
            bookCount = 3,
            role = PersonRole.NARRATOR,
            authorId = "author-4ba6bdc52ed5f262"
        )

        assertNull(
            "the narrator route is identified by name, never re-routed by role luck",
            person.canonicalAuthorId
        )
    }

    @Test
    fun `a stored canonical id wins over the name derivation for an author`() {
        val person = CatalogPerson(
            name = "Лариса Косач",
            path = "",
            bookCount = 1,
            role = PersonRole.AUTHOR,
            authorId = "author-asserted-lesia"
        )

        assertEquals(
            "an asserted alias id is the source of truth when the caller holds it",
            "author-asserted-lesia",
            person.canonicalAuthorId
        )
    }

    @Test
    fun `a nameless author stays null instead of throwing`() {
        val person = CatalogPerson(name = "  ", path = "", bookCount = 0, role = PersonRole.AUTHOR)

        assertNull(person.canonicalAuthorId)
    }
}
