package com.slukhayka.audiobooks.data.collective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #523 — the persisted block envelope: a round-trip keeps every honest fact,
 * and a malformed or foreign document is a cache miss (null), never a crash.
 */
class CollectiveFeedBlockCodecTest {

    private val block = CollectiveFeedBlock(
        blockKey = collectiveBlockKey("soundbooks", CollectiveBlockKind.NEW_ARRIVALS),
        sourceId = "soundbooks",
        kind = CollectiveBlockKind.NEW_ARRIVALS,
        name = "Новинки Sound-Books",
        provenanceUrl = "https://sound-books.net/new",
        cards = listOf(
            CollectiveBlockCard("soundbooks", "https://sound-books.net/kobzar", "Кобзар", "Тарас Шевченко"),
            CollectiveBlockCard("soundbooks", "https://sound-books.net/misto", "Місто", "Підмогильний", "https://c/m.jpg")
        ),
        fetchedAt = 1_700_000_000_000L,
        staleAfter = 1_700_021_600_000L,
        version = 3L,
        lastAttempt = CollectiveAttempt(1_700_000_000_000L, CollectiveAttemptStatus.SUCCESS)
    )

    @Test
    fun `a block round-trips with its order and honest facts`() {
        val decoded = CollectiveFeedBlockCodec.decode(CollectiveFeedBlockCodec.encode(block))

        assertEquals(block, decoded)
        assertEquals(listOf("Кобзар", "Місто"), decoded!!.cards.map { it.title })
    }

    @Test
    fun `a failed attempt round-trips as the recorded status`() {
        val failed = block.copy(lastAttempt = CollectiveAttempt(1_700_000_100_000L, CollectiveAttemptStatus.CHALLENGE))
        val decoded = CollectiveFeedBlockCodec.decode(CollectiveFeedBlockCodec.encode(failed))

        assertEquals(CollectiveAttemptStatus.CHALLENGE, decoded!!.lastAttempt.status)
        assertEquals(failed.cards, decoded.cards)
        assertEquals(3L, decoded.version)
    }

    @Test
    fun `malformed or foreign documents are a miss`() {
        assertNull(CollectiveFeedBlockCodec.decode(""))
        assertNull(CollectiveFeedBlockCodec.decode("not json"))
        assertNull(CollectiveFeedBlockCodec.decode("[]"))
        assertNull(CollectiveFeedBlockCodec.decode("{}"))
        assertNull(CollectiveFeedBlockCodec.decode("""{"blockKey":"x","sourceId":"y"}"""))
    }

    @Test
    fun `block keys are stable and parse back`() {
        val key = collectiveBlockKey("sluhayua", CollectiveBlockKind.COLLECTIONS)
        assertEquals("sluhayua|COLLECTIONS", key)
        assertEquals(
            CollectiveBlockRef("sluhayua", CollectiveBlockKind.COLLECTIONS),
            parseCollectiveBlockKey(key)
        )
        assertNull(parseCollectiveBlockKey("no-separator"))
        assertNull(parseCollectiveBlockKey("sluhayua|NOT_A_KIND"))
        assertNull(parseCollectiveBlockKey("|NEW_ARRIVALS"))
    }

    @Test
    fun `feed keys are distinct per kind`() {
        assertEquals("collective-new_arrivals", collectiveFeedKey(CollectiveBlockKind.NEW_ARRIVALS))
        assertEquals("collective-recommendations", collectiveFeedKey(CollectiveBlockKind.RECOMMENDATIONS))
        assertEquals("collective-collections", collectiveFeedKey(CollectiveBlockKind.COLLECTIONS))
    }
}
