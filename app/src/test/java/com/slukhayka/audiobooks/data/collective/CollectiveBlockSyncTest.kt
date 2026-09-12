package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #527 — the shared block delta: a newer remote observation is mirrored into
 * the local snapshot (so «Огляд» shows it without repeating the source
 * request), an older one is not, the pass is bounded, and a failing transport
 * leaves the cursor and the local blocks untouched.
 */
class CollectiveBlockSyncTest {

    private val key = collectiveBlockKey("audiobookmp3", CollectiveBlockKind.NEW_ARRIVALS)

    private fun block(fetchedAt: Long, title: String = "Книга", version: Long = 1L) = CollectiveFeedBlock(
        blockKey = key,
        sourceId = "audiobookmp3",
        kind = CollectiveBlockKind.NEW_ARRIVALS,
        name = "Новинки audiobook-mp3",
        provenanceUrl = "https://audiobook-mp3.com/uk",
        cards = listOf(
            CollectiveBlockCard("audiobookmp3", "https://audiobook-mp3.com/uk-audio-$title", title, "Автор")
        ),
        fetchedAt = fetchedAt,
        staleAfter = fetchedAt + CollectiveBlockPolicy.NEW_ARRIVALS_TTL_MS,
        version = version,
        lastAttempt = CollectiveAttempt(fetchedAt, CollectiveAttemptStatus.SUCCESS)
    )

    private class FakeStore(private val pages: List<CollectiveBlockPage>, var failing: Boolean = false) :
        CollectiveBlockStore {
        var calls = 0
            private set

        override suspend fun getBlocksPage(after: CollectiveBlockCursor?, limit: Int): CollectiveBlockPage {
            if (failing) throw IllegalStateException("network down")
            val index = calls.coerceAtMost(pages.size - 1)
            calls++
            return pages.getOrElse(index) { CollectiveBlockPage(emptyList(), null) }
        }
    }

    @Test
    fun `a newer remote observation is mirrored locally`() = runBlocking {
        val local = InMemoryCollectiveFeedBlockStore()
        val cursor = CollectiveBlockCursor(10L, "doc-1")
        val store = FakeStore(listOf(CollectiveBlockPage(listOf(block(10L, "Нова")), cursor)))
        val cursorStore = InMemoryCollectiveBlockSyncCursorStore()

        val applied = CollectiveBlockSync(store, local, cursorStore).syncOnce(pageSize = 50)

        assertEquals(1, applied)
        assertEquals(listOf("Нова"), local.active(key)!!.cards.map { it.title })
        assertEquals(cursor, cursorStore.load())
    }

    @Test
    fun `an older remote observation never replaces a newer local one`() = runBlocking {
        val local = InMemoryCollectiveFeedBlockStore()
        local.activate(block(100L, "Локальна"))
        val store = FakeStore(
            listOf(CollectiveBlockPage(listOf(block(10L, "Стара")), CollectiveBlockCursor(10L, "d")))
        )
        val cursorStore = InMemoryCollectiveBlockSyncCursorStore()

        val applied = CollectiveBlockSync(store, local, cursorStore).syncOnce(pageSize = 50)

        assertEquals(0, applied)
        assertEquals(listOf("Локальна"), local.active(key)!!.cards.map { it.title })
        // The cursor still advances: the page WAS consumed.
        assertEquals(CollectiveBlockCursor(10L, "d"), cursorStore.load())
    }

    @Test
    fun `the pass is bounded and a short page ends it`() = runBlocking {
        val local = InMemoryCollectiveFeedBlockStore()
        val store = FakeStore(
            listOf(
                CollectiveBlockPage(List(5) { block(it.toLong() + 1L + 100L, "A$it") }, CollectiveBlockCursor(200L, "d2")),
                CollectiveBlockPage(List(5) { block(it.toLong() + 300L, "B$it") }, CollectiveBlockCursor(400L, "d4"))
            )
        )
        val cursorStore = InMemoryCollectiveBlockSyncCursorStore()

        val applied = CollectiveBlockSync(store, local, cursorStore).syncOnce(pageSize = 10)

        assertEquals(5, applied)
        assertEquals(1, store.calls)
        assertEquals(CollectiveBlockCursor(200L, "d2"), cursorStore.load())
    }

    @Test
    fun `a failing transport leaves the cursor and the local block untouched`() = runBlocking {
        val local = InMemoryCollectiveFeedBlockStore()
        local.activate(block(100L, "Локальна"))
        val store = FakeStore(emptyList(), failing = true)
        val cursorStore = InMemoryCollectiveBlockSyncCursorStore(CollectiveBlockCursor(5L, "d5"))

        val applied = CollectiveBlockSync(store, local, cursorStore).syncOnce()

        assertEquals(0, applied)
        assertEquals(listOf("Локальна"), local.active(key)!!.cards.map { it.title })
        assertEquals(CollectiveBlockCursor(5L, "d5"), cursorStore.load())
    }

    @Test
    fun `a null store is a no-op`() = runBlocking {
        val local = InMemoryCollectiveFeedBlockStore()
        val applied = CollectiveBlockSync(null, local, InMemoryCollectiveBlockSyncCursorStore()).syncOnce()

        assertEquals(0, applied)
        assertNull(local.active(key))
    }
}
