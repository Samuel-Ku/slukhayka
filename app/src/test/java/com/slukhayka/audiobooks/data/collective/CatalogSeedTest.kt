package com.slukhayka.audiobooks.data.collective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #532 — the bundled cold-start seed: bounded, Ukrainian, allowed-source, and
 * carrying ONLY public facts (no track URL, no cookie, no query). The import
 * goes through the ordinary merge-on-write seam, so a rejected entry (a
 * tombstone, a source we no longer serve) is a no-op rather than a failure.
 */
class CatalogSeedTest {

    private fun seedAsset(): File {
        val start = File(System.getProperty("user.dir"))
        return generateSequence(start) { it.parentFile }
            .take(4)
            .map { File(it, "app/src/main/assets/catalog_seed.json") }
            .firstOrNull { it.isFile }
            ?: error("catalog_seed.json not found above ${start.absolutePath}")
    }

    private fun bundledSeed(): List<CollectiveCardPublication> =
        CatalogSeedCodec.parse(seedAsset().readText())

    @Test
    fun `the bundled seed is bounded to the cold-start slice`() {
        val seed = bundledSeed()

        assertTrue("the seed is a START, not a catalogue", seed.size in 20..50)
        assertEquals("no duplicate book may sit in the seed", seed.size, seed.distinctBy { it.sourceUrl }.size)
    }

    @Test
    fun `every seed entry is a publishable Ukrainian book of an allowed source`() {
        for (entry in bundledSeed()) {
            assertTrue("unpublishable seed entry: ${entry.sourceUrl}", CollectiveCardLimits.isPublishable(entry))
            assertEquals("uk", entry.language)
            assertTrue(entry.title.isNotBlank() && entry.author.isNotBlank())
            assertTrue(entry.observedAt > 0L)
        }
    }

    @Test
    fun `no seed entry carries a track url or a cookie`() {
        for (entry in bundledSeed()) {
            val map = CollectiveCardCodec.toMap(entry) ?: error("entry did not encode: ${entry.sourceUrl}")
            assertTrue(
                "a forbidden field rode into the seed: ${map.keys}",
                map.keys.all { it in CollectiveCardCodec.ALLOWED_FIELDS }
            )
            assertFalse(
                "a stream URL must never be seeded: ${entry.sourceUrl}",
                map.values.any { it.toString().contains(".mp3") }
            )
            assertFalse(
                "a cookie must never be seeded",
                map.keys.any { it.contains("cookie", ignoreCase = true) }
            )
        }
    }

    @Test
    fun `the import is idempotent and a rejected entry is a no-op`() = kotlinx.coroutines.runBlocking {
        val seed = bundledSeed()
        val applied = mutableListOf<String>()
        // The second run of the SAME book is rejected (a tombstone, or a
        // source we no longer serve) — it must not throw or be counted.
        var firstRun = true
        val importer = CatalogSeedImporter { entry ->
            applied += entry.sourceUrl
            firstRun
        }

        val first = importer.importOnce(seed)
        firstRun = false
        val second = importer.importOnce(seed)

        assertEquals("every entry lands on the first run", seed.size, first)
        assertEquals("nothing lands twice", 0, second)
        assertEquals("every entry was offered both times", seed.size * 2, applied.size)
    }

    @Test
    fun `a malformed seed document is empty, never a crash`() {
        assertTrue(CatalogSeedCodec.parse("").isEmpty())
        assertTrue(CatalogSeedCodec.parse("[]").isEmpty())
        assertTrue(CatalogSeedCodec.parse("{not an array}").isEmpty())
        assertTrue(
            "a foreign entry is dropped",
            CatalogSeedCodec.parse("""[{"trackUrl":"https://cdn/x.mp3"}]""").isEmpty()
        )
    }
}
