package com.slukhayka.audiobooks.data.recommend

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #481 — the pure recommendation refresh policy: the key moves on signal
 * BOUNDARIES (started / completed / favourite / added) and stays put on an
 * intermediate progress tick. The virtual-time pipeline check pins the same
 * discipline through `distinctUntilChanged` + `debounce`.
 */
class RecommendationRefreshPolicyTest {

    private fun signal(
        id: String = "work",
        isFavorite: Boolean = false,
        started: Boolean = true,
        completed: Boolean = false
    ) = RecommendationRefreshPolicy.WorkSignal(id, isFavorite, started, completed)

    @Test
    fun `intermediate progress produces the same key`() {
        val early = listOf(signal(started = true, completed = false))
        val later = listOf(signal(started = true, completed = false))
        assertEquals(
            RecommendationRefreshPolicy.libraryKey(early),
            RecommendationRefreshPolicy.libraryKey(later)
        )
    }

    @Test
    fun `a boundary moves the key`() {
        val notStarted = listOf(signal(started = false, completed = false))
        val started = listOf(signal(started = true, completed = false))
        val completed = listOf(signal(started = true, completed = true))
        val favorite = listOf(signal(started = true, completed = false, isFavorite = true))

        assertNotEquals(
            RecommendationRefreshPolicy.libraryKey(notStarted),
            RecommendationRefreshPolicy.libraryKey(started)
        )
        assertNotEquals(
            RecommendationRefreshPolicy.libraryKey(started),
            RecommendationRefreshPolicy.libraryKey(completed)
        )
        assertNotEquals(
            RecommendationRefreshPolicy.libraryKey(started),
            RecommendationRefreshPolicy.libraryKey(favorite)
        )
    }

    @Test
    fun `the key is order-independent`() {
        val a = listOf(signal(id = "a"), signal(id = "b"))
        val b = listOf(signal(id = "b"), signal(id = "a"))
        assertEquals(RecommendationRefreshPolicy.libraryKey(a), RecommendationRefreshPolicy.libraryKey(b))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a progress tick does not re-emit, a boundary does, and a batch coalesces once`() = runTest {
        val source = MutableStateFlow(listOf(signal(started = true, completed = false)))
        val emitted = mutableListOf<String>()
        val job = backgroundScope.launch {
            source
                .map { RecommendationRefreshPolicy.libraryKey(it) }
                .distinctUntilChanged()
                .debounce(1_000L)
                .collect { emitted += it }
        }
        runCurrent()
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(1, emitted.size)

        // An intermediate progress tick keeps the same key — no re-emission.
        source.value = listOf(signal(started = true, completed = false))
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, emitted.size)

        // A real boundary emits once.
        source.value = listOf(signal(started = true, completed = true))
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(2, emitted.size)

        // A burst inside the debounce window collapses to one emission.
        source.value = listOf(signal(id = "a", started = true))
        advanceTimeBy(100L)
        source.value = listOf(signal(id = "a", started = true, isFavorite = true))
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(3, emitted.size)

        job.cancel()
    }
}
