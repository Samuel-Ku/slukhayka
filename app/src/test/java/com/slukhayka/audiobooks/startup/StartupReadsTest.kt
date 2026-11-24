package com.slukhayka.audiobooks.startup

import com.slukhayka.audiobooks.data.collective.CollectiveCardPublication
import com.slukhayka.audiobooks.data.collections.CollectionList
import com.slukhayka.audiobooks.data.diagnostics.CrashConsent
import com.slukhayka.audiobooks.data.universe.UniverseList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1723 — the regression witness for the cold-start ANR: every value the old
 * startup path read on the main thread (privacy prefs, crash consent, showcase
 * consent, the curated JSON and the cold-start seed) now goes through
 * [StartupReads.load], which performs the batch on IO exactly once. The
 * privacy route is the documented main-thread exception: it must be installed
 * before the first frame can reach the network.
 *
 * The fake source records the thread of every read, so this is a THREAD
 * assertion, not a proxy: a future edit that removes `withContext(io)` (or
 * calls a source on main before the batch) fails here.
 */
class StartupReadsTest {

    private val entry = CollectiveCardPublication(
        sourceId = "soundbooks",
        sourceUrl = "https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html",
        title = "Темна матерія",
        author = "Блейк Крауч",
        language = "uk",
        observedAt = 1L
    )

    @Test
    fun `the whole startup batch runs off the caller thread once and in order`() = runBlocking {
        val caller = Thread.currentThread()
        val source = RecordingSource(entry = entry, seedDone = false)

        val values = StartupReads(source, Dispatchers.IO).load()

        assertEquals(
            "the batch reads every source exactly once, in the documented order",
            listOf("crash", "showcase", "collections", "universes", "seed-flag", "seed"),
            source.events
        )
        assertTrue(
            "every startup read must happen off the caller (main) thread",
            source.threads.values.all { it !== caller }
        )

        assertEquals(CrashConsent.DENIED, values.crashConsent)
        assertTrue(values.showcasePublished)
        assertEquals(listOf("nobel"), values.collectionLists.map { it.id })
        assertEquals(listOf("first-law"), values.universeLists.map { it.id })
        assertFalse(values.coldStartSeedDone)
        assertEquals(listOf(entry), values.coldStartSeed)
    }

    @Test
    fun `a seeded install never parses the seed asset again`() = runBlocking {
        val source = RecordingSource(entry = entry, seedDone = true)

        val values = StartupReads(source, Dispatchers.IO).load()

        assertTrue(values.coldStartSeedDone)
        assertEquals(
            "the once-per-install flag must skip the JSON parse",
            0L,
            source.seedParses.toLong()
        )
        assertTrue(values.coldStartSeed.isEmpty())
        assertTrue("seed" !in source.events)
    }

    @Test
    fun `the caller thread stays free while the batch runs on IO`() = runBlocking {
        val caller = Thread.currentThread()
        val source = RecordingSource(entry = entry, seedDone = false)

        StartupReads(source, Dispatchers.IO).load()

        assertNotNull("the batch must have read crash consent", source.threads["crash"])
        assertNotSame(caller, source.threads["crash"])
    }

    private class RecordingSource(
        private val entry: CollectiveCardPublication,
        private val seedDone: Boolean,
    ) : StartupSource {
        val events = mutableListOf<String>()
        val threads = mutableMapOf<String, Thread>()
        var seedParses = 0
            private set

        private fun <T> read(name: String, value: T): T {
            events += name
            threads[name] = Thread.currentThread()
            return value
        }

        override fun crashConsent() = read("crash", CrashConsent.DENIED)

        override fun showcasePublished() = read("showcase", true)

        override fun collectionLists() = read(
            "collections",
            listOf(CollectionList(id = "nobel", name = "Нобелівські лауреати"))
        )

        override fun universeLists() = read(
            "universes",
            listOf(UniverseList(id = "first-law", name = "Перший закон"))
        )

        override fun coldStartSeedDone() = read("seed-flag", seedDone)

        override fun coldStartSeed(): List<CollectiveCardPublication> {
            seedParses++
            return read("seed", listOf(entry))
        }
    }
}
