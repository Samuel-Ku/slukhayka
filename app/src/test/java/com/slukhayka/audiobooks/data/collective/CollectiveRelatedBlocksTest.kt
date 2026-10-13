package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.source.RelatedBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CollectiveRelatedBlocksTest {
    private val key = collectiveBlockKey("sluhayua", CollectiveBlockKind.RECOMMENDATIONS)
    private fun detail() = SourceBookDetail(
        title = "Тигролови", author = "Іван Багряний", url = "https://sluhay.com.ua/100:tyhrolovy",
        chapters = emptyList(), related = listOf(
            RelatedBook("Сад Гетсиманський", "Іван Багряний", "https://sluhay.com.ua/101:sad", "https://sluhay.com.ua/sad.jpg"),
            RelatedBook("Місто", "Валер’ян Підмогильний", "https://sluhay.com.ua/102:misto")
        )
    )

    @Test
    fun `already observed recommendations survive the shared wire and reach another client`() = runBlocking {
        val a = InMemoryCollectiveFeedBlockStore()
        val b = InMemoryCollectiveFeedBlockStore()
        var wire: Map<String, Any>? = null
        val shared = object : CollectiveBlockStore {
            override suspend fun putBlock(block: CollectiveFeedBlock) { wire = CollectiveBlockCodec.toMap(block) }
            override suspend fun getBlocksPage(after: CollectiveBlockCursor?, limit: Int) =
                CollectiveBlockPage(listOfNotNull(wire?.let(CollectiveBlockCodec::fromMap)), null)
        }
        val observed = CollectiveFeedRefresh(a, InMemoryCollectiveRefreshLease(),
            fetch = { error("an observation must not fetch a source page") }, clock = { 1_000L },
            onActivated = shared::putBlock)
        observed.observeExplicit(key) { collectiveRelatedBlock("sluhayua", detail()) }
        assertEquals("one shared block reaches B", 1,
            CollectiveBlockSync(shared, b, InMemoryCollectiveBlockSyncCursorStore()).syncOnce())
        val received = b.active(key)!!
        assertEquals("sluhayua|RECOMMENDATIONS", received.blockKey)
        assertEquals("sluhayua", received.sourceId)
        assertEquals("До «Тигролови»", received.name)
        assertEquals("https://sluhay.com.ua/100:tyhrolovy", received.provenanceUrl)
        assertEquals(listOf("Сад Гетсиманський", "Місто"), received.cards.map { it.title })
        assertEquals("https://sluhay.com.ua/sad.jpg", received.cards.first().coverUrl)
        assertEquals(1L, received.version)
        assertEquals(1_000L + 24L * 60 * 60 * 1_000L, received.staleAfter)
    }

    @Test
    fun `recommendations keep first Work and URL in source order without self or invalid cards`() {
        val observed = detail().copy(related = listOf(
            RelatedBook("Сад Гетсиманський", "Іван Багряний", "https://sluhay.com.ua/101:sad"),
            RelatedBook("Сад Гетсиманський: інше видання", "Іван Багряний", "https://sluhay.com.ua/103:sad-other"),
            RelatedBook("Місто", "Валер’ян Підмогильний", "https://sluhay.com.ua/102:misto"),
            RelatedBook("Інша назва того самого URL", "Інший автор", "https://sluhay.com.ua/102:misto"),
            RelatedBook("Тигролови", "Іван Багряний", "https://sluhay.com.ua/100:tyhrolovy"),
            RelatedBook("Тигролови", "Іван Багряний", "https://sluhay.com.ua/104:self-edition"),
            RelatedBook("", "Автор", "https://sluhay.com.ua/invalid-title"),
            RelatedBook("Без URL", "Автор", ""),
            RelatedBook("Без відомого автора", "", "https://sluhay.com.ua/105:unknown"),
            RelatedBook("Без відомого автора", "", "https://sluhay.com.ua/106:unknown-other")
        ))
        val outcome = collectiveRelatedBlock("sluhayua", observed)
        assertTrue(outcome is CollectiveRefreshOutcome.Success)
        val cards = (outcome as CollectiveRefreshOutcome.Success).block.cards
        assertEquals(listOf("https://sluhay.com.ua/101:sad", "https://sluhay.com.ua/102:misto",
            "https://sluhay.com.ua/105:unknown", "https://sluhay.com.ua/106:unknown-other"), cards.map { it.sourceUrl })
        assertEquals(listOf("Сад Гетсиманський", "Місто", "Без відомого автора", "Без відомого автора"), cards.map { it.title })
    }

    @Test
    fun `empty or invalid detail observations preserve the last good block and shared version`() = runBlocking {
        val local = InMemoryCollectiveFeedBlockStore()
        var publications = 0
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no linked page fetching") }, clock = { 1_000L },
            onActivated = { publications++ })
        val good = refresh.observeExplicit(key) { collectiveRelatedBlock("sluhayua", detail()) }!!
        for (invalid in listOf(detail().copy(related = emptyList()), detail().copy(title = ""),
            detail().copy(url = ""), detail().copy(related = listOf(RelatedBook("", "", ""))))) {
            val retained = refresh.observeExplicit(key) { collectiveRelatedBlock("sluhayua", invalid) }!!
            assertEquals(good.cards, retained.cards)
            assertEquals(good.provenanceUrl, retained.provenanceUrl)
            assertEquals(1L, retained.version)
            assertEquals(CollectiveAttemptStatus.EMPTY, retained.lastAttempt.status)
        }
        assertEquals(1, publications)
        assertEquals(CollectiveRefreshOutcome.Empty, collectiveRelatedBlock("4read", detail()))
        val afterFailure = refresh.observeExplicit(key) { throw java.io.IOException("page failed") }!!
        assertEquals(good.cards, afterFailure.cards)
        assertEquals(1, publications)
    }

    @Test
    fun `cancelled observation preserves the previous block and never publishes again`() {
        val local = InMemoryCollectiveFeedBlockStore()
        var publications = 0
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no source I/O") }, clock = { 1_000L }, onActivated = { publications++ })
        val good = runBlocking { refresh.observeExplicit(key) { collectiveRelatedBlock("sluhayua", detail()) } }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { refresh.observeExplicit(key) { throw kotlinx.coroutines.CancellationException("left") } }
        }
        assertEquals(good, runBlocking { local.active(key) })
        assertEquals(1, publications)
    }
}
