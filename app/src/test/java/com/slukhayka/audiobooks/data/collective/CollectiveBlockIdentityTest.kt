package com.slukhayka.audiobooks.data.collective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1238 — identity carries the canonical provenance context: one Source's
 * related collections are different blocks, their rows differ, and the wire
 * codec refuses an identity that names a different provenance than the block
 * carries. The legacy #527 key stays readable.
 */
class CollectiveBlockIdentityTest {

    private fun card(title: String = "Книга") = CollectiveBlockCard(
        sourceId = "soundbooks",
        sourceUrl = "https://sound-books.net/$title",
        title = title,
        author = "Автор"
    )

    private fun block(key: String, provenanceUrl: String) = CollectiveFeedBlock(
        blockKey = key,
        sourceId = "soundbooks",
        kind = CollectiveBlockKind.COLLECTIONS,
        name = "Добірка",
        provenanceUrl = provenanceUrl,
        cards = listOf(card()),
        fetchedAt = 1_000L,
        staleAfter = 2_000L,
        version = 1L,
        lastAttempt = CollectiveAttempt(1_000L, CollectiveAttemptStatus.SUCCESS)
    )

    @Test
    fun `canonical context drops transport noise and keeps the page`() {
        assertEquals(
            "sound-books.net/collections/do-a",
            canonicalProvenanceContext("HTTPS://Sound-Books.NET:443/collections/do-a/#top")
        )
        assertEquals(
            "sound-books.net/collections/do-a",
            canonicalProvenanceContext("https://sound-books.net/collections/do-a/")
        )
        assertEquals(
            "sound-books.net/collections?sort=new",
            canonicalProvenanceContext("https://sound-books.net/collections?sort=new")
        )
        assertEquals("", canonicalProvenanceContext("   "))
        assertEquals("папка", canonicalProvenanceContext("папка"))
        assertFalse(
            "the key separator never leaks into a context",
            canonicalProvenanceContext("https://x.example/a|b").contains('|')
        )
    }

    @Test
    fun `related collections A and B get different identities and rows`() {
        val a = collectiveGenreBlockKey("soundbooks", "/collections/do-a")
        val b = collectiveGenreBlockKey("soundbooks", "/collections/do-b")

        assertNotEquals(a, b)
        assertEquals(CollectiveBlockKind.COLLECTIONS, parseCollectiveBlockKey(a)!!.kind)
        assertEquals("sound-books.net/collections/do-a", parseCollectiveBlockKey(a)!!.provenanceContext)
        assertEquals("sound-books.net/collections/do-b", parseCollectiveBlockKey(b)!!.provenanceContext)
        assertEquals(a, collectiveBlockKey(parseCollectiveBlockKey(a)!!))

        val refA = parseCollectiveBlockKey(a)!!
        val refB = parseCollectiveBlockKey(b)!!
        assertNotEquals(collectiveFeedKey(refA), collectiveFeedKey(refB))
        assertNotEquals(collectiveBlockDocumentId(refA), collectiveBlockDocumentId(refB))
        assertEquals(collectiveBlockDocumentId(refA), collectiveBlockDocumentId(parseCollectiveBlockKey(a)!!))
    }

    @Test
    fun `the legacy pre-1238 key is still an identity`() {
        val legacy = collectiveBlockKey("soundbooks", CollectiveBlockKind.NEW_ARRIVALS)
        val ref = parseCollectiveBlockKey(legacy)!!

        assertEquals("soundbooks", ref.sourceId)
        assertEquals(CollectiveBlockKind.NEW_ARRIVALS, ref.kind)
        assertEquals(LEGACY_BLOCK_CONTEXT, ref.provenanceContext)
        assertEquals("collective-new_arrivals", collectiveFeedKey(ref))
        assertEquals("soundbooks-new_arrivals", collectiveBlockDocumentId(ref))
        assertNull("an empty third segment is not an identity", parseCollectiveBlockKey("soundbooks|NEW_ARRIVALS|"))
    }

    @Test
    fun `the wire codec refuses an identity that names another provenance`() {
        val a = collectiveGenreBlockKey("soundbooks", "/collections/do-a")
        val bProvenance = "https://sound-books.net/collections/do-b"

        assertNull("key says A, block carries B", CollectiveBlockCodec.toMap(block(a, bProvenance)))
        assertNull(
            CollectiveBlockCodec.fromMap(
                CollectiveBlockCodec.toMap(
                    block(a, "https://sound-books.net/collections/do-a")
                )!! + (CollectiveBlockCodec.FIELD_PROVENANCE_URL to bProvenance)
            )
        )
        assertTrue(
            CollectiveBlockCodec.toMap(
                block(a, "https://sound-books.net/collections/do-a")
            ) != null
        )
    }

    @Test
    fun `a legacy key does not claim a context`() {
        val legacy = collectiveBlockKey("soundbooks", CollectiveBlockKind.COLLECTIONS)

        val decoded = CollectiveBlockCodec.fromMap(
            CollectiveBlockCodec.toMap(block(legacy, "https://sound-books.net/collections/old"))!!
        )

        assertEquals(LEGACY_BLOCK_CONTEXT, parseCollectiveBlockKey(decoded!!.blockKey)!!.provenanceContext)
    }

    @Test
    fun `A and B coexist in the local store`() = kotlinx.coroutines.runBlocking {
        val local = InMemoryCollectiveFeedBlockStore()
        val a = collectiveGenreBlockKey("soundbooks", "/collections/do-a")
        val b = collectiveGenreBlockKey("soundbooks", "/collections/do-b")
        val blockA = block(a, "https://sound-books.net/collections/do-a").copy(cards = listOf(card("А")))
        val blockB = block(b, "https://sound-books.net/collections/do-b").copy(cards = listOf(card("Б")))

        assertTrue(local.activate(blockA))
        assertTrue(local.activate(blockB))

        assertEquals(listOf("А"), local.active(a)!!.cards.map { it.title })
        assertEquals(listOf("Б"), local.active(b)!!.cards.map { it.title })
    }
}
