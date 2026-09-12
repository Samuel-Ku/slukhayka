package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #522 — the bounded cursor delta: it applies accepted cards, advances the
 * durable cursor only after a page, stops on a short page or a page bound,
 * and leaves the cursor untouched when the transport fails.
 */
class CollectiveDeltaSyncTest {

    private fun card(title: String, observedAt: Long = 1L) = CollectiveCardPublication(
        sourceId = "soundbooks",
        sourceUrl = "https://sound-books.net/${title.lowercase()}",
        title = title,
        author = "Автор",
        language = "uk",
        observedAt = observedAt
    )

    private class FakeStore(
        private val pages: List<CollectivePage>,
        var failing: Boolean = false
    ) : CollectiveCardStore {
        var calls = 0
            private set
        var lastAfter: CollectiveCursor? = null
            private set

        override suspend fun getCardsPage(after: CollectiveCursor?, limit: Int): CollectivePage {
            if (failing) throw IllegalStateException("network down")
            lastAfter = after
            val index = calls.coerceAtMost(pages.size - 1)
            calls++
            return pages.getOrElse(index) { CollectivePage(emptyList(), null) }
        }
    }

    @Test
    fun `a page is applied and its cursor committed`() = runBlocking {
        val cursor = CollectiveCursor(10L, "doc-1")
        val store = FakeStore(listOf(CollectivePage(listOf(card("А"), card("Б")), cursor)))
        val cursorStore = InMemoryCollectiveSyncCursorStore()
        val applied = mutableListOf<String>()

        val count = CollectiveDeltaSync(store, { applied += it.title; true }, cursorStore)
            .syncOnce(pageSize = 50)

        assertEquals(2, count)
        assertEquals(listOf("А", "Б"), applied)
        assertEquals(cursor, cursorStore.load())
    }

    @Test
    fun `a short page ends the pass`() = runBlocking {
        val store = FakeStore(
            listOf(
                CollectivePage(listOf(card("А")), CollectiveCursor(1L, "d1")),
                CollectivePage(listOf(card("Б")), CollectiveCursor(2L, "d2"))
            )
        )
        val cursorStore = InMemoryCollectiveSyncCursorStore()

        // pageSize 50 > 1 card → the first page is terminal.
        val count = CollectiveDeltaSync(store, { true }, cursorStore).syncOnce(pageSize = 50)

        assertEquals(1, count)
        assertEquals(1, store.calls)
        assertEquals(CollectiveCursor(1L, "d1"), cursorStore.load())
    }

    @Test
    fun `the pass is bounded by its page limit`() = runBlocking {
        val fullPages = (1..10).map { index ->
            CollectivePage(
                List(10) { card("К$index-$it") },
                CollectiveCursor(index.toLong(), "d$index")
            )
        }
        val store = FakeStore(fullPages)
        val cursorStore = InMemoryCollectiveSyncCursorStore()

        val count = CollectiveDeltaSync(
            store,
            { true },
            cursorStore,
            maxPages = 2
        ).syncOnce(pageSize = 10)

        assertEquals(20, count)
        assertEquals(2, store.calls)
        assertEquals(CollectiveCursor(2L, "d2"), cursorStore.load())
    }

    @Test
    fun `a failing transport leaves the cursor and the mirror untouched`() = runBlocking {
        val store = FakeStore(emptyList(), failing = true)
        val cursorStore = InMemoryCollectiveSyncCursorStore(CollectiveCursor(5L, "d5"))
        var applied = 0

        val count = CollectiveDeltaSync(store, { applied++; true }, cursorStore).syncOnce()

        assertEquals(0, count)
        assertEquals(0, applied)
        assertEquals(CollectiveCursor(5L, "d5"), cursorStore.load())
    }

    @Test
    fun `a rejected card is not counted and never aborts the page`() = runBlocking {
        val store = FakeStore(
            listOf(CollectivePage(listOf(card("А"), card("Б")), CollectiveCursor(1L, "d1")))
        )
        val cursorStore = InMemoryCollectiveSyncCursorStore()

        val count = CollectiveDeltaSync(
            store,
            { it.title == "Б" },
            cursorStore
        ).syncOnce(pageSize = 50)

        assertEquals(1, count)
        assertEquals(CollectiveCursor(1L, "d1"), cursorStore.load())
    }

    @Test
    fun `a null store is a no-op`() = runBlocking {
        var applied = 0
        val count = CollectiveDeltaSync(null, { applied++; true }, InMemoryCollectiveSyncCursorStore())
            .syncOnce()
        assertEquals(0, count)
        assertEquals(0, applied)
    }

    @Test
    fun `a non-advancing cursor stops the pass`() = runBlocking {
        val same = CollectiveCursor(7L, "d7")
        val store = FakeStore(
            listOf(
                CollectivePage(List(10) { card("А$it") }, same),
                CollectivePage(List(10) { card("Б$it") }, same)
            )
        )
        // The lane already committed this cursor: a page that returns it again
        // would re-apply the same cards, so the pass stops after one read.
        val cursorStore = InMemoryCollectiveSyncCursorStore(same)

        val count = CollectiveDeltaSync(store, { true }, cursorStore).syncOnce(pageSize = 10)

        assertEquals(0, count)
        assertEquals(1, store.calls)
        assertEquals(same, cursorStore.load())
    }

    @Test
    fun `an empty terminal page advances the cursor once`() = runBlocking {
        val cursor = CollectiveCursor(9L, "d9")
        val store = FakeStore(listOf(CollectivePage(emptyList(), cursor)))
        val cursorStore = InMemoryCollectiveSyncCursorStore()

        val count = CollectiveDeltaSync(store, { true }, cursorStore).syncOnce()

        assertEquals(0, count)
        assertEquals(cursor, cursorStore.load())
    }

    @Test
    fun `a zero page size never reads`() = runBlocking {
        val store = FakeStore(listOf(CollectivePage(listOf(card("А")), CollectiveCursor(1L, "d1"))))
        val count = CollectiveDeltaSync(store, { true }, InMemoryCollectiveSyncCursorStore())
            .syncOnce(pageSize = 0)

        assertEquals(0, count)
        assertNull(store.lastAfter)
    }
}
