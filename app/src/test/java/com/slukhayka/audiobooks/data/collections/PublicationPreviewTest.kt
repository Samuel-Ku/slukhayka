package com.slukhayka.audiobooks.data.collections

import org.junit.Assert.assertEquals
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
    fun `the preview lists exactly what travels`() {
        val preview = PublicationPreviewFactory.of(collection(books = arrayOf("a", "b")), "Слухач")!!

        assertEquals("Магія", preview.title)
        assertEquals("Слухач", preview.pseudonym)
        assertEquals(2, preview.bookCount)
        assertTrue(preview.descriptionIncluded)
        assertEquals(
            listOf(
                "Назва: Магія",
                "Псевдонім: Слухач",
                "Книг у добірці: 2",
                "Опис: буде опубліковано"
            ),
            preview.lines
        )
    }

    @Test
    fun `an absent description is stated honestly, not implied`() {
        val preview = PublicationPreviewFactory.of(collection(description = "", books = arrayOf("a")), "Слухач")!!
        assertTrue(preview.lines.last() == "Опис: не додано")
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
