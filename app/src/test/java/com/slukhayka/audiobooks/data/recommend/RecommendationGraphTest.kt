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
            val fetch: (String, Int) -> String? = { url, _ ->
                if (url.endsWith(".sig")) signature else {
                    bodies++
                    if (bodies == 1) artifact().replace("87400000", (now + 3 * 86_400_000L).toString())
                    else "broken"
                }
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
            val fetch: (String, Int) -> String? = { url, _ ->
                if (url.endsWith(".sig")) signed
                else artifact().replace("87400000", "604800000")
            }
            fun store() = RecommendationGraphStore(directory, "https://example.org/graph.json",
                verify, fetch) { time }
            assertNotNull(store().refresh())
            signed = "attacker"
            time += 86_400_001L
            assertNotNull("the good cache must survive a bad signature", store().refresh())
        } finally { directory.deleteRecursively() }
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
