package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.collective.CatalogSeedCodec
import com.slukhayka.audiobooks.data.collections.MiniJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worker's real catalogue is generated from the app's bundled seed by
 * `scripts/recommend/catalog_from_seed.py`. That Python port normalizes titles
 * and authors; this test proves its `workKey`s are exactly the app's
 * [com.slukhayka.audiobooks.data.merge.MergeKey.keyFor] values, so the graph
 * can never be keyed by a lookalike identity.
 */
class SeedCatalogKeyTest {
    private fun repoFile(path: String): File {
        val start = File(System.getProperty("user.dir"))
        return generateSequence(start) { it.parentFile }
            .take(5)
            .map { File(it, path) }
            .firstOrNull { it.isFile }
            ?: error("$path not found above ${start.absolutePath}")
    }

    @Test
    fun `worker catalogue keys match the app merge key`() {
        val cards = CatalogSeedCodec.parse(
            repoFile("app/src/main/assets/catalog_seed.json").readText()
        )
        val document = MiniJson.parse(
            repoFile("scripts/recommend/seed-catalog.json").readText()
        ) as? Map<*, *> ?: error("catalogue is not an object")
        val works = document["works"] as? List<*> ?: error("catalogue has no works")
        val keys = works.map { row ->
            ((row as? Map<*, *>)?.get("workKey") as? String) ?: error("work has no key")
        }

        assertEquals("one worker row per seed card", cards.size, works.size)
        assertEquals("no duplicate work keys", keys.size, keys.toSet().size)
        assertEquals("worker keys are the app merge keys", cards.map { it.mergeKey }.toSet(), keys.toSet())
        for (card in cards) {
            assertTrue("missing app key ${card.mergeKey}", card.mergeKey in keys)
        }
    }
}
