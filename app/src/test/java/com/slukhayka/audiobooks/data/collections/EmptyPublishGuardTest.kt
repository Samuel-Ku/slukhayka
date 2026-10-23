package com.slukhayka.audiobooks.data.collections

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1405 — «порожню добірку не можна опублікувати» is a MODEL invariant, not a
 * UI state: the publish sheet merely hides the button. Every door out of the
 * device (the one factory that builds the document, the shared store, the gate
 * in front of it) must refuse an empty composition, and `firestore.rules`
 * rejects an empty `bookIds` even from a caller that skips all three.
 */
class EmptyPublishGuardTest {

    private val author = "a".repeat(64)

    private fun collection(items: List<ListenerCollectionItem>) = ListenerCollection(
        id = "c1",
        title = "Моя добірка",
        description = "",
        createdAt = 1L,
        items = items
    )

    private fun item(bookId: String) = ListenerCollectionItem(
        bookId = bookId,
        reason = "",
        addedAt = 1L
    )

    @Test
    fun `the factory never builds a document from an empty collection`() {
        assertNull(
            "an empty published document would be invisible on every honest surface",
            PublishedCollectionFactory.of(collection(emptyList()), author, "Читач", 1L)
        )
    }

    @Test
    fun `the factory still builds a real composition`() {
        val document = PublishedCollectionFactory.of(
            collection(listOf(item("book-1"), item("book-2"))),
            author,
            "Читач",
            1L
        )
        assertNotNull(document)
        assertEquals(listOf("book-1", "book-2"), document!!.bookIds)
        assertEquals(document.bookIds.size, document.reasons.size)
        assertEquals(document.bookIds.size, document.items.size)
    }

    @Test
    fun `the shared store refuses an empty collection before writing`() = runBlocking {
        val store = InMemorySharedCollections()

        val result = store.publish(collection(emptyList()), author, "Читач")

        assertEquals(PublishResult.Refused("no-books"), result)
        assertTrue("nothing may be published", store.publishedBy(author).isEmpty())
    }

    @Test
    fun `the gate refuses through the same store seam`() = runBlocking {
        val gate = PublicCollectionsGate(InMemorySharedCollections())

        val result = gate.publish(collection(emptyList()), author, "Читач")

        assertEquals(PublishResult.Refused("no-books"), result)
    }
}
