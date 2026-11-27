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

    @Test fun `related discovery rotates while strongest personal picks stay stable`() {
        val candidates = (1..60).map {
            RecommendationEngine.Candidate("match-$it", "Назва $it", author = "Автор $it", genre = "фентезі")
        }
        val signal = RecommendationEngine.Signal("seed", "Магічний світ", genre = "фентезі", weight = 1.0)
        val ranker = LocalRecommendationRanker()
        val first = ranker.rank(candidates, listOf(signal), nowEpochMs = 86_400_000)
        val second = ranker.rank(candidates.reversed(), listOf(signal), nowEpochMs = 172_800_000)
        assertEquals(first.take(8), second.take(8))
        assertNotEquals(first.drop(8).map { it.candidate.id }, second.drop(8).map { it.candidate.id })
        assertTrue((first + second).all { it.semanticScore > 0 })
    }

    @Test fun `cold start prefers confirmed source popularity before arbitrary rotation`() {
        val top = RecommendationEngine.Candidate("source-top", "Визнана книжка")
        val candidates = (1..100).map { RecommendationEngine.Candidate("ordinary-$it", "Назва $it") } + top
        val result = LocalRecommendationRanker().rank(candidates, emptyList(), topN = 1,
            nowEpochMs = now, popularityByWorkId = mapOf(top.id to 1.0),
            sourceLabelsByWorkId = mapOf(top.id to "source"))
        assertEquals(top.id, result.single().candidate.id)
        assertEquals("source", result.single().sourceLabel)
    }

    @Test fun `sixty fifth explicit dislike still vetoes related discovery`() {
        val negatives = (0..64).map {
            RecommendationEngine.Signal("neg-${it.toString().padStart(2, '0')}", "Rejected$it", author = "Author$it", weight = -1.0)
        }
        val target = RecommendationEngine.Candidate("target", "UnrelatedNovel", author = "Author64")
        assertTrue(LocalRecommendationRanker().rank(listOf(target), negatives).isEmpty())
    }

    @Test fun `sixty fifth graph only dislike still vetoes related discovery`() {
        val ids = (0..64).map { "neg-${it.toString().padStart(2, '0')}" } + "target"
        val rows = ids.indices.joinToString(",") { if (it == 64) "[[65,0.9]]" else "[]" }
        val wire = graphText().replace("[\"covered-a\",\"covered-b\"]", ids.joinToString(",", "[", "]") { "\"$it\"" })
            .replace("[[[1,0.9]],[[0,0.9]]]", "[$rows]")
        val negatives = ids.dropLast(1).map { RecommendationEngine.Signal(it, it, weight = -1.0) }
        assertTrue(LocalRecommendationRanker().rank(listOf(RecommendationEngine.Candidate("target", "Other")),
            negatives, nowEpochMs = now, contentGraph = PrecomputedRecommendationGraph.parse(wire, now)!!).isEmpty())
    }

    @Test fun `negative index agrees with exhaustive graph and metadata scoring`() {
        val random = java.util.Random(1825)
        val ranker = LocalRecommendationRanker()
        repeat(50) {
            val ids = listOf("target", "positive") + (0 until 90).map { "negative-$it" }
            val edges = ids.indices.map { mutableListOf<Pair<Int, Double>>() }
            edges[0] += 1 to .95
            // Independently choose each edge direction, including absent edges.
            for (i in 2 until ids.size) {
                edges[i].clear()
                edges[0].removeAll { it.first == i }
                if (random.nextBoolean()) {
                    val value = random.nextDouble()
                    if (random.nextBoolean() && edges[0].size < 32) edges[0] += i to value else edges[i] += 0 to value
                }
            }
            val rows = edges.joinToString(",", "[", "]") { row ->
                row.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }
            }
            val wire = graphText().replace("[\"covered-a\",\"covered-b\"]",
                ids.joinToString(",", "[", "]") { "\"$it\"" })
                .replace("[[[1,0.9]],[[0,0.9]]]", rows)
            val graph = PrecomputedRecommendationGraph.parse(wire, now)!!
            fun feature() = RecommendationFeatures("author${random.nextInt(4)}", "series${random.nextInt(4)}",
                (0..3).filter { random.nextBoolean() }.mapTo(mutableSetOf()) { "genre$it" })
            val candidate = feature()
            val negatives = ids.drop(2).map { id ->
                val f = feature()
                RecommendationEngine.Signal(id, id, author = f.author, series = f.series,
                    genre = f.genres.joinToString(" "), weight = -.3 * random.nextDouble()) to f
            }
            val positive = RecommendationEngine.Signal("positive", "Positive", author = candidate.author,
                series = candidate.series, genre = candidate.genres.joinToString(" "), weight = 1.5)
            val expectedPositive = (.5 * .95 + candidate.affinity(candidate)) * 1.5
            val expectedNegative = negatives.maxOf { (signal, f) ->
                (.5 * graph.similarity("target", signal.id) + candidate.affinity(f)) * -signal.weight
            }
            val actual = ranker.rank(listOf(RecommendationEngine.Candidate("target", "Target",
                author = candidate.author, series = candidate.series, genre = candidate.genres.joinToString(" "))),
                negatives.map { it.first } + positive, nowEpochMs = now, contentGraph = graph).single()
            assertEquals(expectedPositive - expectedNegative, actual.semanticScore, 1e-9)
        }
    }

    @Test fun `negative cache follows positive changes metadata updates and removal`() {
        val ranker = LocalRecommendationRanker()
        val target = RecommendationEngine.Candidate("target", "Novel", author = "Author")
        val negative = RecommendationEngine.Signal("negative", "Rejected", author = "Author", weight = -1.0)
        assertTrue(ranker.rank(listOf(target), listOf(negative)).isEmpty())
        val positive = RecommendationEngine.Signal("positive", "Favourite", author = "Author", weight = 1.5)
        assertEquals("target", ranker.rank(listOf(target), listOf(negative, positive)).single().candidate.id)
        assertEquals("target", ranker.rank(listOf(target.copy(author = "Other")), listOf(negative)).single().candidate.id)
        assertTrue(ranker.rank(listOf(target), listOf(negative)).isEmpty())
        assertEquals("target", ranker.rank(listOf(target), emptyList()).single().candidate.id)
        val beforeOff = ranker.rank(listOf(target), listOf(negative, positive))
        ranker.clearPersonalization()
        assertEquals(beforeOff, ranker.rank(listOf(target), listOf(negative, positive)))
        assertTrue(ranker.rank(listOf(target), listOf(negative)).isEmpty())
    }

    @Test fun `discarded graph clears negative veto even after its weak reference is collected`() {
        val ranker = LocalRecommendationRanker()
        val target = RecommendationEngine.Candidate("covered-a", "Novel")
        val negative = RecommendationEngine.Signal("covered-b", "Rejected", weight = -1.0)
        assertTrue(ranker.rank(listOf(target), listOf(negative), nowEpochMs = now,
            contentGraph = PrecomputedRecommendationGraph.parse(graphText(), now)).isEmpty())
        // Deterministically model collection between graph expiry and the next rank.
        val reference = LocalRecommendationRanker::class.java.getDeclaredField("negativeGraph").apply { isAccessible = true }
        (reference.get(ranker) as java.lang.ref.WeakReference<*>).clear()
        assertEquals("covered-a", ranker.rank(listOf(target), listOf(negative), nowEpochMs = now).single().candidate.id)
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
