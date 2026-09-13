package com.slukhayka.audiobooks.data.collections

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicCollectionsGateTest {

    private val authorId = CuratorIdentity.authorId("test-uid-1")

    private fun collection() = ListenerCollection(
        id = "c1",
        title = "Магія",
        description = "",
        createdAt = 1L,
        items = listOf(ListenerCollectionItem("a", "", 1L))
    )

    @Test
    fun `without a shared store there is no public surface`() = runBlocking {
        val gate = PublicCollectionsGate(null)

        assertFalse(gate.available)
        assertEquals(PublishResult.Refused(PublicCollectionsGate.NO_SHARED_STORE),
            gate.publish(collection(), authorId, "Слухач"))
        assertEquals(PublishResult.Refused(PublicCollectionsGate.NO_SHARED_STORE),
            gate.renameAuthor(authorId, "Новий"))
        assertEquals(PublishResult.Refused(PublicCollectionsGate.NO_SHARED_STORE),
            gate.deleteAuthorProfile(authorId))
        assertTrue("nothing public to read", gate.publishedBy(authorId).isEmpty())
    }

    @Test
    fun `with a shared store the gate simply delegates`() = runBlocking {
        val store = InMemorySharedCollections()
        val gate = PublicCollectionsGate(store)

        assertTrue(gate.available)
        assertEquals(PublishResult.Published, gate.publish(collection(), authorId, "Слухач"))
        assertEquals(1, gate.publishedBy(authorId).size)
        assertEquals(PublishResult.Published, gate.renameAuthor(authorId, "Новий"))
        assertEquals("Новий", gate.publishedBy(authorId).single().pseudonym)
    }
}
