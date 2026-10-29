package com.slukhayka.audiobooks.ui.collections

import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1606 — the «Колекції» index is LIVE while open.
 *
 * The old ViewModel copied `smartCollections.value` once, so a union refresh
 * that landed while the index screen was open never reached it (the Огляд,
 * collecting the same flow, did update). The module keeps the old snapshot as
 * its first frame and then follows the flow until close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsIndexModuleTest {

    private fun book(title: String) = GlobalSearchResult(
        title = title,
        author = "Автор",
        mergeKey = title.lowercase() + "|автор",
        coverImageUrl = null,
        sources = emptyList()
    )

    private fun collection(id: String, vararg titles: String) =
        CollectionMatcher.MatchedCollection(
            id = id,
            name = "Колекція $id",
            sourceNote = "assets",
            books = titles.map(::book)
        )

    @Test
    fun `opening publishes the current snapshot at once`() = runTest {
        val first = collection("nobel", "Старий і море")
        val source = MutableStateFlow(listOf(first))
        val module = CollectionsIndexModule(source, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        module.open()

        assertTrue(module.isOpen.value)
        assertEquals(listOf(first), module.collections.value)
    }

    @Test
    fun `a refresh while open updates the index live`() = runTest {
        val first = collection("nobel", "Старий і море")
        val source = MutableStateFlow(listOf(first))
        val module = CollectionsIndexModule(source, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        module.open()

        val refreshed = listOf(first, collection("shevchenko", "Кобзар"))
        source.value = refreshed

        assertEquals("the Огляд already follows this flow; the index must too", refreshed, module.collections.value)
    }

    @Test
    fun `closing stops following the flow and resets the list`() = runTest {
        val first = collection("nobel", "Старий і море")
        val source = MutableStateFlow(listOf(first))
        val module = CollectionsIndexModule(source, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        module.open()

        module.close()
        source.value = listOf(collection("shevchenko", "Кобзар"))

        assertFalse(module.isOpen.value)
        assertEquals(emptyList<CollectionMatcher.MatchedCollection>(), module.collections.value)
    }

    @Test
    fun `reopening after a close publishes the newest snapshot again`() = runTest {
        val first = collection("nobel", "Старий і море")
        val source = MutableStateFlow(listOf(first))
        val module = CollectionsIndexModule(source, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        module.open()
        module.close()

        val refreshed = listOf(first, collection("shevchenko", "Кобзар"))
        source.value = refreshed
        module.open()

        assertEquals(refreshed, module.collections.value)
    }
}
