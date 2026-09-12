package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #532 — the once-per-install rule: a clean start seeds the local catalogue,
 * every later launch is a no-op, and a failing import never hangs the start.
 */
class ColdStartSeedTest {

    private val entry = CollectiveCardPublication(
        sourceId = "soundbooks",
        sourceUrl = "https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html",
        title = "Темна матерія",
        author = "Блейк Крауч",
        language = "uk",
        observedAt = 1L
    )

    @Test
    fun `a clean install seeds once and never again`() = runBlocking {
        val flag = InMemoryColdStartSeedFlag()
        var applies = 0
        val seed = ColdStartSeed(CatalogSeedImporter { applies++; true }, listOf(entry), flag)

        assertTrue("the first launch seeds", seed.runOnce())
        assertEquals(1, applies)
        assertTrue(flag.isDone())

        assertFalse("every later launch is a no-op", seed.runOnce())
        assertEquals("the seed is not re-applied", 1, applies)
    }

    @Test
    fun `an empty seed finishes without hanging the start`() = runBlocking {
        val flag = InMemoryColdStartSeedFlag()
        val seed = ColdStartSeed(CatalogSeedImporter { true }, emptyList(), flag)

        assertFalse(seed.runOnce())
        assertTrue("the start is not left spinning", flag.isDone())
    }

    @Test
    fun `a per-entry failure is a no-op, not a failed cold start`() = runBlocking {
        val flag = InMemoryColdStartSeedFlag()
        val seed = ColdStartSeed(CatalogSeedImporter { throw IllegalStateException("browser down") }, listOf(entry), flag)

        assertFalse("nothing landed", seed.runOnce())
        assertTrue("the seed is consumed, so the start never loops", flag.isDone())
    }

    @Test
    fun `cancellation is never swallowed by the seed`() = runBlocking {
        val flag = InMemoryColdStartSeedFlag()
        val seed = ColdStartSeed(
            CatalogSeedImporter { throw kotlinx.coroutines.CancellationException("scoped away") },
            listOf(entry),
            flag
        )

        var cancelled = false
        try {
            seed.runOnce()
        } catch (expected: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }

        assertTrue("cancellation propagates", cancelled)
        assertFalse("a cancelled start may retry later", flag.isDone())
    }
}
