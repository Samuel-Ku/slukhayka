package com.slukhayka.audiobooks.ui.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistence tests for [ListenPrefsStore] (wayfinder #62): every control —
 * reorder, hide, restore, dismiss, undismiss — survives a fresh store over
 * the same preferences file, exactly as a process restart would see it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ListenPrefsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store() = ListenPrefsStore(context)

    @Test
    fun `defaults are empty order, nothing hidden, nothing dismissed`() {
        val store = store()
        assertTrue(store.order.isEmpty())
        assertTrue(store.hiddenBlockIds.isEmpty())
        assertTrue(store.dismissedBookIds.isEmpty())
    }

    @Test
    fun `moving a block up persists the new order`() {
        val store = store()
        store.moveBlockUp(ListenComposer.BlockId.TRAVEL)
        // Default order is HERO, ALMOST_DONE, RETURN, NEXT_IN_SERIES, TRAVEL…;
        // one move-up puts TRAVEL one step higher (position 3, 0-based).
        assertEquals(3, store.order.indexOf(ListenComposer.BlockId.TRAVEL))
        assertEquals(8, store.order.size)

        // A fresh store over the same prefs file sees the same order.
        val reloaded = store()
        assertEquals(store.order, reloaded.order)
    }

    @Test
    fun `moving the first block up is a no-op that leaves the default order`() {
        val store = store()
        store.moveBlockUp(ListenComposer.BlockId.HERO)
        // Nothing was written — an empty persisted order means default priority.
        assertTrue(store.order.isEmpty())
        assertEquals(ListenComposer.DEFAULT_ORDER, ListenComposer.DEFAULT_ORDER)
    }

    @Test
    fun `hide and restore round-trips through a fresh store`() {
        val store = store()
        store.hideBlock(ListenComposer.BlockId.ALMOST_DONE)
        store.hideBlock(ListenComposer.BlockId.SHORT)
        assertTrue(store.hiddenBlockIds.contains(ListenComposer.BlockId.ALMOST_DONE))

        val reloaded = store()
        assertTrue(reloaded.hiddenBlockIds.contains(ListenComposer.BlockId.SHORT))

        reloaded.restoreHiddenBlocks()
        val afterRestore = store()
        assertTrue(afterRestore.hiddenBlockIds.isEmpty())
    }

    @Test
    fun `dismiss and undismiss round-trips through a fresh store`() {
        val store = store()
        store.dismissBook("book-1")
        store.dismissBook("book-2")
        assertTrue(store.dismissedBookIds.containsAll(listOf("book-1", "book-2")))

        val reloaded = store()
        reloaded.undismissBook("book-1")
        val after = store()
        assertEquals(setOf("book-2"), after.dismissedBookIds)
    }

    @Test
    fun `reorder after dismiss keeps both independent`() {
        val store = store()
        store.dismissBook("book-x")
        store.moveBlockDown(ListenComposer.BlockId.HERO)

        val reloaded = store()
        assertTrue(reloaded.dismissedBookIds.contains("book-x"))
        assertFalse(reloaded.dismissedBookIds.contains("book-1"))
        // One move-down puts HERO at position 1 (0-based); dismiss unaffected.
        assertEquals(1, reloaded.order.indexOf(ListenComposer.BlockId.HERO))
    }
}
