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

    @Test
    fun `one vote per person updates the aggregate transactionally`() = runBlocking {
        val store = InMemorySharedCollections()
        store.publish(collection("c1", "book-a"), authorId, "Слухач")
        val document = store.publishedBy(authorId).single()
        val voter = CollectionIdentity.voterKey("voter-uid", document.collectionId)

        assertEquals(PublishResult.Published, store.vote(document.documentId, voter, 5))
        val rated = store.publishedBy(authorId).single()
        assertEquals(5, rated.ratingSum)
        assertEquals(1, rated.ratingCount)
        assertEquals(5, store.myVote(voter))

        // A re-vote REPLACES the person's stars: still one voter.
        assertEquals(PublishResult.Published, store.vote(document.documentId, voter, 2))
        val reRated = store.publishedBy(authorId).single()
        assertEquals(2, reRated.ratingSum)
        assertEquals(1, reRated.ratingCount)
    }

    @Test
    fun `two people are two independent votes`() = runBlocking {
        val store = InMemorySharedCollections()
        store.publish(collection("c1", "book-a"), authorId, "Слухач")
        val document = store.publishedBy(authorId).single()

        store.vote(document.documentId, CollectionIdentity.voterKey("a", "c1"), 4)
        store.vote(document.documentId, CollectionIdentity.voterKey("b", "c1"), 5)

        val rated = store.publishedBy(authorId).single()
        assertEquals(9, rated.ratingSum)
        assertEquals(2, rated.ratingCount)
    }

    @Test
    fun `a bad vote is refused and never touches the aggregate`() = runBlocking {
        val store = InMemorySharedCollections()
        store.publish(collection("c1", "book-a"), authorId, "Слухач")
        val document = store.publishedBy(authorId).single()

        assertTrue(store.vote(document.documentId, "", 5) is PublishResult.Refused)
        assertTrue(store.vote(document.documentId, "key", 0) is PublishResult.Refused)
        assertTrue(store.vote(document.documentId, "key", 6) is PublishResult.Refused)
        assertTrue(store.vote("missing", "key", 5) is PublishResult.Refused)

        val untouched = store.publishedBy(authorId).single()
        assertEquals(0, untouched.ratingSum)
        assertEquals(0, untouched.ratingCount)

        val offline = InMemorySharedCollections(online = false)
        assertTrue(offline.vote(document.documentId, "key", 5) is PublishResult.Refused)
    }

    @Test
    fun `collections containing one book are found by their book list`() = runBlocking {
        val store = InMemorySharedCollections()
        store.publish(collection("c1", "book-a", "book-b"), authorId, "Слухач")
        store.publish(collection("c2", "book-c"), authorId, "Слухач")

        assertEquals(listOf("c1"), store.containing("book-a").map { it.collectionId })
        assertEquals(listOf("c1"), store.containing("book-b").map { it.collectionId })
        assertTrue(store.containing("nobody").isEmpty())
        assertTrue("a blank book id is never a query", store.containing("").isEmpty())
    }

    @Test
    fun `the published document carries each book's reason so a fork can keep it`() = runBlocking {
        val store = InMemorySharedCollections()
        val original = ListenerCollection(
            id = "c1",
            title = "Магія",
            description = "про зорі",
            createdAt = 1L,
            items = listOf(
                ListenerCollectionItem("a", "бо атмосферно", 1L),
                ListenerCollectionItem("b", "", 2L)
            )
        )
        store.publish(original, authorId, "Слухач")
        val published = store.publishedBy(authorId).single()
        assertEquals(listOf("бо атмосферно", ""), published.reasons)

        // What Firestore really stores and reads back is the ENCODED shape.
        val decoded = PublishedCollectionCodec.decode(PublishedCollectionCodec.encode(published))!!
        assertEquals(listOf("бо атмосферно", ""), decoded.reasons)

        // And that is exactly what «Зберегти собі» rebuilds the fork from.
        val rebuilt = ListenerCollection(
            id = decoded.collectionId,
            title = decoded.title,
            description = decoded.description,
            createdAt = decoded.publishedAt,
            items = decoded.bookIds.mapIndexed { index, bookId ->
                ListenerCollectionItem(bookId, decoded.reasons.getOrElse(index) { "" }, decoded.publishedAt)
            }
        )
        val (forked, _) = ForkPolicy.forkOf(rebuilt, decoded.pseudonym, decoded.documentId, now = 100L)
        assertEquals(listOf("a", "b"), forked.items.map { it.bookId })
        assertEquals("бо атмосферно", forked.items.first().reason)
    }
}
