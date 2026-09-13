package com.slukhayka.audiobooks.data.collections

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerCollectionsSharedStoreTest {

    private val uid = "test-uid-1"
    private val authorId = CuratorIdentity.authorId(uid)

    private fun collection(id: String, vararg books: String) = ListenerCollection(
        id = id,
        title = "Магія",
        description = "про зорі",
        createdAt = 1L,
        items = books.map { ListenerCollectionItem(it, "", 1L) }
    )

    @Test
    fun `publishing needs an identity and a pseudonym`() = runBlocking {
        val store = InMemorySharedCollections()

        assertTrue(store.publish(collection("c1"), CuratorIdentity.authorId(""), "Слухач") is PublishResult.Refused)
        assertTrue(store.publish(collection("c1"), authorId, "   ") is PublishResult.Refused)
        assertEquals(PublishResult.Published, store.publish(collection("c1"), authorId, "Слухач"))
        assertEquals(1, store.publishedBy(authorId).size)
    }

    @Test
    fun `offline refuses honestly and publishes nothing`() = runBlocking {
        val store = InMemorySharedCollections(online = false)
        assertEquals(PublishResult.Refused("offline"), store.publish(collection("c1"), authorId, "Слухач"))
        assertTrue(store.publishedBy(authorId).isEmpty())
    }

    @Test
    fun `renaming the pseudonym updates every own collection`() = runBlocking {
        val store = InMemorySharedCollections()
        store.publish(collection("c1"), authorId, "Старий")
        store.publish(collection("c2"), authorId, "Старий")

        assertEquals(PublishResult.Published, store.renameAuthor(authorId, "Новий"))
        assertEquals(listOf("Новий", "Новий"), store.publishedBy(authorId).map { it.pseudonym })
    }

    @Test
    fun `deleting the profile removes the author and all their collections`() = runBlocking {
        val store = InMemorySharedCollections()
        store.publish(collection("c1"), authorId, "Слухач")
        store.publish(collection("c2"), authorId, "Слухач")
        val otherAuthor = CuratorIdentity.authorId("someone-else")
        store.publish(collection("c3"), otherAuthor, "Інший")

        assertEquals(PublishResult.Published, store.deleteAuthorProfile(authorId))
        assertTrue(store.publishedBy(authorId).isEmpty())
        assertEquals("other curators survive", 1, store.publishedBy(otherAuthor).size)
    }
}
