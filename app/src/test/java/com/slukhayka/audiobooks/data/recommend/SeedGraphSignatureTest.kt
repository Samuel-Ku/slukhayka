package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.collections.MiniJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end proof of the real path: the signed graph was built by the worker
 * from the app's bundled seed catalogue, the signature is over the exact bytes,
 * and the Android verifier + parser accept it. The signing key used for this
 * fixture is discarded; only its public key is committed.
 */
class SeedGraphSignatureTest {
    private fun resource(name: String): String =
        javaClass.getResource("/recommend/$name")?.readText() ?: error("missing $name")

    private fun repoFile(path: String): File {
        val start = File(System.getProperty("user.dir"))
        return generateSequence(start) { it.parentFile }
            .take(5)
            .map { File(it, path) }
            .firstOrNull { it.isFile }
            ?: error("$path not found above ${start.absolutePath}")
    }

    private val body = resource("seed-graph.json")
    private val signature = resource("seed-graph.json.sig").trim()
    private val publicKey = resource("seed-graph.pub").trim()

    @Test fun `real worker graph verifies and parses`() {
        val verifier = GraphSignatureVerifier(publicKey)
        assertTrue(verifier.verify(body, signature))
        assertFalse(verifier.verify(body.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), signature))
        assertFalse(verifier.verify(body, signature.dropLast(6) + "AAAAAA"))
        assertNotNull(PrecomputedRecommendationGraph.parse(body, 1_000_000))
    }

    @Test fun `graph keys are exactly the app seed catalogue keys`() {
        val document = MiniJson.parse(body) as? Map<*, *> ?: error("graph is not an object")
        val graphKeys = (document["workKeys"] as? List<*>)?.map { it as String } ?: error("no workKeys")
        val catalog = MiniJson.parse(repoFile("scripts/recommend/seed-catalog.json").readText()) as? Map<*, *>
            ?: error("catalogue is not an object")
        val catalogKeys = (catalog["works"] as? List<*>)
            ?.map { (it as Map<*, *>)["workKey"] as String }?.toSet() ?: error("no works")
        assertEquals(catalogKeys, graphKeys.toSet())
    }

    @Test fun `graph carries a usable semantic edge for the real catalogue`() {
        val document = MiniJson.parse(body) as? Map<*, *> ?: error("graph is not an object")
        val keys = (document["workKeys"] as List<*>).map { it as String }
        val rows = document["neighbors"] as List<*>
        val source = rows.indexOfFirst { (it as List<*>).isNotEmpty() }
        assertTrue("the real graph must contain at least one edge", source >= 0)
        val edge = (rows[source] as List<*>)[0] as List<*>
        val target = (edge[0] as Double).toInt()
        val score = edge[1] as Double
        val graph = PrecomputedRecommendationGraph.parse(body, 1_000_000) ?: error("graph did not parse")
        assertTrue(graph.similarity(keys[source], keys[target]) >= score - 1e-9)
    }
}
