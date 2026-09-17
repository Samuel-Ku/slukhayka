package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.authors.AuthorSummary
import com.slukhayka.audiobooks.data.db.PersonRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #874 — the authors index and the person page share ONE identity. */
class AuthorAsSelectedPersonTest {

    @Test
    fun `an author becomes the person route with the author role`() {
        val person = AuthorSummary(
            id = "shevchenko",
            displayName = "Тарас Шевченко",
            normalizedName = "шевченко тарас",
            workCount = 12
        ).asSelectedPerson()

        assertEquals("Тарас Шевченко", person.name)
        assertEquals(PersonRole.AUTHOR, person.role)
        assertEquals("the canonical id rides along", "shevchenko", person.authorId)
        assertEquals("an author is local, not a source page", "", person.path)
    }

    @Test
    fun `a narrator person carries no author id`() {
        val narrator = SelectedPerson("Іван Франко", "", PersonRole.NARRATOR)

        assertNull("a narrator is identified by name, not by an author id", narrator.authorId)
        assertEquals(PersonRole.NARRATOR, narrator.role)
    }

    @Test
    fun `the role never becomes part of the address`() {
        val author = AuthorSummary("id", "Імʼя", "імʼя", 1).asSelectedPerson()
        val narrator = SelectedPerson("Імʼя", "", PersonRole.NARRATOR)

        assertEquals(
            "the same person has the same address whatever the role",
            author.name to author.path,
            narrator.name to narrator.path
        )
    }
}
