package com.slukhayka.audiobooks.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationPreviewTest {

    private fun collection(
        title: String = "Магія",
        description: String = "про зорі",
        vararg books: String
    ) = ListenerCollection(
        id = "c1",
        title = title,
        description = description,
        createdAt = 1L,
        items = books.map { ListenerCollectionItem(it, "", 1L) }
    )

    @Test
    fun `the preview carries exactly what travels`() {
        val preview = PublicationPreviewFactory.of(collection(books = arrayOf("a", "b")), "Слухач")!!

        assertEquals("Магія", preview.title)
        assertEquals("Слухач", preview.pseudonym)
        assertEquals(2, preview.bookCount)
        assertTrue(preview.descriptionIncluded)
    }

    @Test
    fun `an absent description is stated honestly, not implied`() {
        val preview = PublicationPreviewFactory.of(collection(description = "", books = arrayOf("a")), "Слухач")!!

        // #980: the label moved to the sheet's resources, so the data class
        // states the fact and the EN walk checks the wording on screen.
        assertFalse("a blank description must not travel as included", preview.descriptionIncluded)
    }

    @Test
    fun `an unpublishable collection has no preview at all`() {
        assertNull("no books", PublicationPreviewFactory.of(collection(), "Слухач"))
        assertNull(
            "no usable title",
            PublicationPreviewFactory.of(collection(title = "https://spam.example", books = arrayOf("a")), "Слухач")
        )
        assertNull(
            "no pseudonym",
            PublicationPreviewFactory.of(collection(books = arrayOf("a")), "   ")
        )
    }
}
