package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RecommendationGraphTest {
    private val now = 1_000_000L
    private fun artifact() = """{"schemaVersion":1,"model":"minishlab/potion-multilingual-128M",
        "revision":"${"a".repeat(40)}","catalogSha256":"${"b".repeat(64)}",
        "generatedAt":1000000,"expiresAt":87400000,
        "workKeys":["seed","target","noise"],"neighbors":[[[1,0.9]],[[0,0.9]],[]]}"""

    private val signature = "test-signature"
    private val verify: (String, String) -> Boolean = { _, signed -> signed == signature }

    @Test fun `graph supplies semantic match without lexical overlap`() {
        val wire = javaClass.getResource("/recommend/content-graph.json")!!.readText()
        val graph = PrecomputedRecommendationGraph.parse(wire, now)!!
        val candidates = listOf(RecommendationEngine.Candidate("target", "Невідома назва"),
            RecommendationEngine.Candidate("noise", "Друга назва"))
        val result = LocalRecommendationRanker().rank(candidates,
            listOf(RecommendationEngine.Signal("seed", "Інший заголовок", weight = 1.0)),
            nowEpochMs = now, topN = 1, contentGraph = graph)
        assertEquals("target", result.single().candidate.id)
        assertEquals("Інший заголовок", result.single().reasonTitle)
    }

    @Test fun `invalid graphs cannot enter ranking`() {
        val bad = listOf(
            artifact().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            artifact().replace("87400000", now.toString()),
            artifact().replace("87400000", (now + 8 * 86_400_000L).toString()),
            artifact().replace("a".repeat(40), "main"),
            artifact().replace("target", "seed"),
            artifact().replace("1,0.9", "0,0.9"),
            artifact().replace("1,0.9", "99,0.9"),
            artifact().replace("1,0.9", "1,1.5"))
        bad.forEach { assertNull(PrecomputedRecommendationGraph.parse(it.toString(), now)) }
        assertNull(PrecomputedRecommendationGraph.parse(artifact().toString(), now + 86_400_000L))
    }

    @Test fun `store keeps good cache on failure and throttles across restarts`() {
        val directory = Files.createTempDirectory("recommend-graph").toFile()
        try {
            var time = now
            var bodies = 0
            val fetch: (String, Int) -> String? = { _, _ ->
                bodies++
                if (bodies == 1) SignedGraphEnvelope.encode(
                    artifact().replace("87400000", (now + 3 * 86_400_000L).toString()), signature)
                else "broken"
            }
            fun store() = RecommendationGraphStore(directory, "https://example.org/graph.json",
                verify, fetch) { time }
            assertNotNull(store().refresh())
            assertNotNull(store().refresh())
            assertEquals(1, bodies)
            time += 86_400_001L
            assertNotNull(store().refresh())
            assertEquals(2, bodies)
            time += 4 * 86_400_000L
            assertNull(store().refresh())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `invalid signature never replaces the cached graph`() {
        val directory = Files.createTempDirectory("recommend-signature").toFile()
        try {
            var time = now
            var signed = signature
            val fetch: (String, Int) -> String? = { _, _ ->
                SignedGraphEnvelope.encode(artifact().replace("87400000", "604800000"), signed)
            }
            fun store() = RecommendationGraphStore(directory, "https://example.org/graph.json",
                verify, fetch) { time }
            assertNotNull(store().refresh())
            val accepted = java.io.File(directory, "content-graph.signed.json").readText()
            signed = "attacker"
            time += 86_400_001L
            assertNotNull("the good cache must survive a bad signature", store().refresh())
            assertEquals(accepted, java.io.File(directory, "content-graph.signed.json").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `pending update cannot destroy the last complete cached envelope`() {
        val directory = Files.createTempDirectory("recommend-atomic").toFile()
        try {
            val envelope = SignedGraphEnvelope.encode(artifact(), signature)
            val store = RecommendationGraphStore(directory, "https://example.org/graph.json", verify,
                { _, _ -> envelope }) { now }
            assertNotNull(store.refresh())
            // Simulate interruption after staging the next version but before the single rename.
            java.io.File(directory, "content-graph.signed.tmp").writeText("incomplete update")
            val offline = RecommendationGraphStore(directory, "", verify, { _, _ -> error("network") }) { now }
            assertNotNull(offline.refresh())
            assertEquals(envelope, java.io.File(directory, "content-graph.signed.json").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `old detached cache remains readable during migration`() {
        val directory = Files.createTempDirectory("recommend-legacy").toFile()
        try {
            java.io.File(directory, "content-graph.json").writeText(artifact())
            java.io.File(directory, "content-graph.json.sig").writeText(signature)
            val store = RecommendationGraphStore(directory, "", verify, { _, _ -> error("network") }) { now }
            assertNotNull(store.refresh())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `envelope round trip preserves exact signed graph text`() {
        val body = artifact() + "\n"
        val encoded = SignedGraphEnvelope.encode(body, signature)
        val decoded = SignedGraphEnvelope.decode(encoded)!!
        assertEquals(body, decoded.graph)
        assertEquals(signature, decoded.signature)
        assertNull(SignedGraphEnvelope.decode(body))
        assertNull(SignedGraphEnvelope.decode(encoded.replace("\"formatVersion\":1", "\"formatVersion\":2")))
    }

    @Test fun `unsigned cached graph is ignored`() {
        val directory = Files.createTempDirectory("recommend-unsigned").toFile()
        try {
            directory.mkdirs()
            java.io.File(directory, "content-graph.json").writeText(artifact())
            val graph = RecommendationGraphStore(directory, "", verify, { _, _ -> null }) { now }.refresh()
            assertNull(graph)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `unset endpoint never makes network request`() {
        val directory = Files.createTempDirectory("recommend-no-endpoint").toFile()
        try {
            assertNull(RecommendationGraphStore(directory, "", verify, { _, _ -> error("network") }) { now }.refresh())
        } finally { directory.deleteRecursively() }
    }
}
