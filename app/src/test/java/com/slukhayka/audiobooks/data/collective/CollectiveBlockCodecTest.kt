package com.slukhayka.audiobooks.data.collective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #527 — the SHARED block wire shape: one allowed field set (a document with
 * a query, a cookie or a track URL is a miss), a round-trip that keeps the
 * source's card order, and identity consistency between the key and the fields.
 */
class CollectiveBlockCodecTest {

    private val key = collectiveBlockKey("audiobookmp3", CollectiveBlockKind.NEW_ARRIVALS)

    private fun block(
        cards: List<CollectiveBlockCard> = listOf(
            CollectiveBlockCard("audiobookmp3", "https://audiobook-mp3.com/uk-audio-1-a", "Перша", "Автор"),
            CollectiveBlockCard("audiobookmp3", "https://audiobook-mp3.com/uk-audio-2-b", "Друга", "Автор", "https://c/x.jpg")
        )
    ) = CollectiveFeedBlock(
        blockKey = key,
        sourceId = "audiobookmp3",
        kind = CollectiveBlockKind.NEW_ARRIVALS,
        name = "Новинки audiobook-mp3",
        provenanceUrl = "https://audiobook-mp3.com/uk",
        cards = cards,
        fetchedAt = 1_700_000_000_000L,
        staleAfter = 1_700_021_600_000L,
        version = 3L,
        lastAttempt = CollectiveAttempt(1_700_000_000_000L, CollectiveAttemptStatus.SUCCESS)
    )

    @Test
    fun `a block round-trips with its order and identity`() {
        val encoded = CollectiveBlockCodec.toMap(block())
        val decoded = CollectiveBlockCodec.fromMap(encoded!!)

        assertEquals(key, decoded!!.blockKey)
        assertEquals("audiobookmp3", decoded.sourceId)
        assertEquals(CollectiveBlockKind.NEW_ARRIVALS, decoded.kind)
        assertEquals(3L, decoded.version)
        assertEquals(listOf("Перша", "Друга"), decoded.cards.map { it.title })
        assertEquals("https://c/x.jpg", decoded.cards[1].coverUrl)
    }

    @Test
    fun `an empty or identity-less block is never encoded`() {
        assertNull(CollectiveBlockCodec.toMap(block(cards = emptyList())))
        assertNull(CollectiveBlockCodec.toMap(block().copy(blockKey = "no-separator")))
        assertNull(CollectiveBlockCodec.toMap(block().copy(sourceId = "")))
        assertNull(CollectiveBlockCodec.toMap(block().copy(name = "")))
        assertNull(CollectiveBlockCodec.toMap(block().copy(provenanceUrl = "")))
    }

    @Test
    fun `a document with a query, cookie or track url is a miss`() {
        val base = CollectiveBlockCodec.toMap(block())!!.toMutableMap()

        val withQuery = base.toMutableMap().apply { put("query", "кобзар") }
        assertNull(CollectiveBlockCodec.fromMap(withQuery))

        val withCookies = base.toMutableMap().apply { put("cookies", "cf_clearance=abc") }
        assertNull(CollectiveBlockCodec.fromMap(withCookies))

        val withTrack = base.toMutableMap().apply { put("trackUrl", "https://cdn/x.mp3") }
        assertNull(CollectiveBlockCodec.fromMap(withTrack))
    }

    @Test
    fun `a card with a forbidden field rejects the document`() {
        val cards = listOf(
            mapOf(
                "sourceId" to "audiobookmp3",
                "sourceUrl" to "https://audiobook-mp3.com/uk-audio-1-a",
                "title" to "Книга",
                "author" to "Автор",
                "trackUrl" to "https://cdn/x.mp3"
            )
        )
        val document = CollectiveBlockCodec.toMap(block())!!.toMutableMap().apply { put("cards", cards) }

        assertNull(CollectiveBlockCodec.fromMap(document))
    }

    @Test
    fun `an inconsistent identity or missing timestamp is a miss`() {
        val base = CollectiveBlockCodec.toMap(block())!!

        assertNull(CollectiveBlockCodec.fromMap(base + ("sourceId" to "soundbooks")))
        assertNull(
            CollectiveBlockCodec.fromMap(
                base + (CollectiveBlockCodec.FIELD_KIND to CollectiveBlockKind.COLLECTIONS.name)
            )
        )
        assertNull(CollectiveBlockCodec.fromMap(base - CollectiveBlockCodec.FIELD_FETCHED_AT))
        assertNull(CollectiveBlockCodec.fromMap(base + ("cards" to emptyList<Any>())))
    }

    @Test
    fun `the JSON door decodes the same shape`() {
        val json = """
            {"blockKey":"audiobookmp3|NEW_ARRIVALS","sourceId":"audiobookmp3","kind":"NEW_ARRIVALS",
             "name":"Новинки","provenanceUrl":"https://audiobook-mp3.com/uk","fetchedAt":10,
             "staleAfter":21610,"version":1,
             "cards":[{"sourceId":"audiobookmp3","sourceUrl":"https://audiobook-mp3.com/uk-audio-1-a",
                       "title":"Книга","author":"Автор"}]}
        """.trimIndent()

        val decoded = CollectiveBlockCodec.fromJson(json)

        assertEquals(1, decoded!!.cards.size)
        assertEquals("Книга", decoded.cards.single().title)
        assertNull(CollectiveBlockCodec.fromJson("{not json}"))
        assertTrue(CollectiveBlockCodec.fromJson("[]") == null)
    }
}
