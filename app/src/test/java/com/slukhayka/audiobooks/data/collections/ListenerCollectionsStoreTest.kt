package com.slukhayka.audiobooks.data.collections

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerCollectionsStoreTest {

    private fun store(vararg ticks: Long): ListenerCollectionsStore {
        val queue = ticks.toMutableList()
        return InMemoryListenerCollections { if (queue.isEmpty()) 0L else queue.removeAt(0) }
    }

    @Test
    fun `a new collection is hygienic and appears in the list`() = runBlocking {
        val store = store(100L)
        val id = store.create("  Космос https://spam.example ", "Про зорі\n\nі людей")

        assertEquals("Космос", store.all().single().title)
        assertEquals("Про зорі і людей", store.all().single().description)
        assertEquals("c1", id)
    }

    @Test
    fun `a title that is only a link cannot create a collection`() = runBlocking {
        val store = store()
        assertNull(store.create("https://spam.example", null))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `books keep insertion order and the first real one is the cover`() = runBlocking {
        val store = store(1L, 2L, 3L)
        val id = store.create("Магія", null)!!
        assertTrue(store.add(id, "book-a", "бо атмосферно"))
        assertTrue(store.add(id, "book-b", null))

        val collection = store.all().single()
        assertEquals(listOf("book-a", "book-b"), collection.items.map { it.bookId })
        assertEquals("book-a", collection.coverBookId)
        assertEquals("бо атмосферно", collection.items.first().reason)
    }

    @Test
    fun `adding the same book twice is an honest no-op`() = runBlocking {
        val store = store(1L, 2L)
        val id = store.create("Магія", null)!!
        assertTrue(store.add(id, "book-a", null))
        assertFalse("already there", store.add(id, "book-a", null))
        assertEquals(1, store.all().single().items.size)
    }

    @Test
    fun `removing works and unknown ids report false, never crash`() = runBlocking {
        val store = store(1L)
        val id = store.create("Магія", null)!!
        store.add(id, "book-a", null)

        assertTrue(store.remove(id, "book-a"))
        assertTrue(store.all().single().items.isEmpty())
        assertFalse(store.remove(id, "book-a"))
        assertFalse(store.remove("missing", "book-a"))
    }

    @Test
    fun `a collection with no items has no cover`() = runBlocking {
        val store = store(1L)
        val id = store.create("Порожня", null)!!
        assertNull(store.all().single { it.id == id }.coverBookId)
    }
}
