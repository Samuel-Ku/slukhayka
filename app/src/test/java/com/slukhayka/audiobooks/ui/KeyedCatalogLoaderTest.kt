package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.catalog.CatalogFetchResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KeyedCatalogLoaderTest {

    @Test
    fun `fast B wins after delayed A even when A ignores cancellation`() = runTest {
        var completeA: (CatalogFetchResult<List<String>>) -> Unit = {}
        val loader = KeyedCatalogLoader<String, String>(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler)
        ) { key ->
            if (key == "A") {
                suspendCoroutine { continuation ->
                    completeA = continuation::resume
                }
            } else {
                CatalogFetchResult.Success(listOf("book-B"))
            }
        }

        loader.open("A")
        runCurrent()
        loader.open("B")
        runCurrent()

        assertEquals(
            CatalogDestinationLoadState.Ready(listOf("book-B")),
            loader.state.value
        )
        assertEquals(listOf("book-B"), loader.items.value)
        assertEquals(false, loader.isLoading.value)
        assertEquals(false, loader.failed.value)

        completeA(CatalogFetchResult.Success(listOf("stale-book-A")))
        runCurrent()

        assertEquals(
            CatalogDestinationLoadState.Ready(listOf("book-B")),
            loader.state.value
        )
    }

    @Test
    fun `close stays closed after delayed request returns`() = runTest {
        var complete: (CatalogFetchResult<List<String>>) -> Unit = {}
        val loader = KeyedCatalogLoader<String, String>(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler)
        ) {
            suspendCoroutine { continuation ->
                complete = continuation::resume
            }
        }

        loader.open("A")
        runCurrent()
        loader.close()

        assertEquals(CatalogDestinationLoadState.Closed, loader.state.value)

        complete(CatalogFetchResult.Success(listOf("late")))
        runCurrent()

        assertEquals(CatalogDestinationLoadState.Closed, loader.state.value)
    }

    @Test
    fun `catalog failure remains an explicit route error`() = runTest {
        val loader = KeyedCatalogLoader<String, String>(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler)
        ) { CatalogFetchResult.Failure }

        loader.open("A")
        runCurrent()

        assertEquals(CatalogDestinationLoadState.Failed, loader.state.value)
        assertEquals(emptyList<String>(), loader.items.value)
        assertEquals(false, loader.isLoading.value)
        assertEquals(true, loader.failed.value)
    }
}
