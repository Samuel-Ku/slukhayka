package com.slukhayka.audiobooks.data.recommend

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RecommendationQualityRegressionTest {
    private val now = 1_000_000L
    private fun graphText() = """{"schemaVersion":1,"model":"minishlab/potion-multilingual-128M",
        "revision":"${"a".repeat(40)}","catalogSha256":"${"b".repeat(64)}",
        "generatedAt":1000000,"expiresAt":87400000,
        "workKeys":["covered-a","covered-b"],"neighbors":[[[1,0.9]],[[0,0.9]]]}"""

    @Test fun `partial graph preserves text recommendations for uncovered works`() {
        val candidates = listOf(
            RecommendationEngine.Candidate("seed", "Експедиція", description = "археолог руїни експедиція"),
            RecommendationEngine.Candidate("new", "Знахідка", description = "археолог руїни експедиція")
        )
        val result = LocalRecommendationRanker().rank(candidates,
            listOf(RecommendationEngine.Signal("seed", "Експедиція", weight = 1.0)),
            nowEpochMs = now, topN = 1,
            contentGraph = PrecomputedRecommendationGraph.parse(graphText(), now)).single()
        assertEquals("new", result.candidate.id)
        assertTrue("An unrelated graph must not erase the textual match", result.semanticScore > 0)
        assertEquals("Експедиція", result.reasonTitle)
    }

    @Test fun `discovery prefers remaining relevant works before unrelated rotation`() {
        val candidates = (1..25).map {
            RecommendationEngine.Candidate("match-$it", "Фентезі $it", author = "Автор $it", genre = "фентезі")
        } + (1..100).map {
            RecommendationEngine.Candidate("noise-$it", "Кухня $it", author = "Кухар $it", genre = "кулінарія")
        }
        val result = LocalRecommendationRanker().rank(candidates,
            listOf(RecommendationEngine.Signal("seed", "Магічний світ", genre = "фентезі", weight = 1.0)),
            nowEpochMs = now)
        assertEquals(10, result.size)
        assertTrue("Relevant alternatives are available for every slot", result.all { it.candidate.genre == "фентезі" })
    }

    @Test fun `duplicate metadata cannot bypass hidden author and first work wins`() {
        val hidden = RecommendationEngine.Candidate("duplicate", "Hidden book", author = "Hidden")
        val visible = hidden.copy(title = "Visible book", author = "Visible")
        val ranker = LocalRecommendationRanker()
        assertTrue(ranker.rank(listOf(hidden, visible), emptyList(), excludedAuthors = setOf("Hidden")).isEmpty())
        assertEquals(listOf("Visible"), ranker.rank(listOf(visible, hidden), emptyList(),
            excludedAuthors = setOf("Hidden")).map { it.candidate.author })
    }

    @Test fun `covered missing edge remains authoritative despite matching text`() {
        val graph = PrecomputedRecommendationGraph.parse(graphText()
            .replace("[[[1,0.9]],[[0,0.9]]]", "[[],[]]"), now)!!
        val candidates = listOf("covered-a", "covered-b").map {
            RecommendationEngine.Candidate(it, "археолог руїни", description = "експедиція")
        }
        val result = LocalRecommendationRanker().rank(candidates,
            listOf(RecommendationEngine.Signal("covered-a", "археолог руїни", weight = 1.0)),
            nowEpochMs = now, contentGraph = graph).single()
        assertEquals(0.0, result.semanticScore, 0.0)
        assertTrue(result.reasonTitle.isEmpty())
    }

    @Test fun `text only dislike still vetoes uncovered works with a partial graph`() {
        val result = LocalRecommendationRanker().rank(
            listOf(RecommendationEngine.Candidate("new", "Нова назва", description = "привиди темрява жахи")),
            listOf(RecommendationEngine.Signal("disliked", "привиди темрява жахи", weight = -1.0)),
            nowEpochMs = now, contentGraph = PrecomputedRecommendationGraph.parse(graphText(), now))
        assertTrue(result.isEmpty())
    }

    @Test fun `compact graph preserves unsorted symmetric edges and missing work distinction`() {
        val wire = graphText().replace("\"workKeys\":[\"covered-a\",\"covered-b\"]",
            "\"workKeys\":[\"covered-a\",\"covered-b\",\"covered-c\",\"isolated\"]")
            .replace("\"neighbors\":[[[1,0.9]],[[0,0.9]]]", "\"neighbors\":[[[2,0.3],[1,0.9]],[],[],[]]")
        val graph = PrecomputedRecommendationGraph.parse(wire, now)!!
        assertEquals(.9, graph.similarity("covered-b", "covered-a"), 0.0)
        assertEquals(.3, graph.similarity("covered-a", "covered-c"), 0.0)
        assertEquals(0.0, graph.similarity("covered-b", "covered-c"), 0.0)
        assertTrue(graph.containsWork("isolated"))
        assertFalse(graph.containsWork("new"))
    }

    @Test fun `unchanged verified graph is reused within a store and still expires`() {
        val directory = Files.createTempDirectory("graph-reuse").toFile()
        try {
            var time = now
            var verifications = 0
            var downloads = 0
            val store = RecommendationGraphStore(directory, "https://example.org/graph",
                { _, _ -> verifications++; true },
                { _, _ -> downloads++; SignedGraphEnvelope.encode(graphText(), "signature") }) { time }
            val accepted = store.refresh()
            assertNotNull(accepted)
            assertSame(accepted, store.refresh())
            assertEquals(1, verifications)
            assertEquals(1, downloads)
            time += 2 * 86_400_000L
            assertNull(store.refresh())
        } finally { directory.deleteRecursively() }
    }
}
