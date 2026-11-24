package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.*
import org.junit.Test

class LocalRecommendationRankerTest {
    private fun book(id: String, genre: String = "", author: String = id, series: String = "", description: String = "") =
        RecommendationEngine.Candidate(id, id, author, genre, series, description)
    private fun signal(id: String, genre: String = "", author: String = id, weight: Double = 1.0) =
        RecommendationEngine.Signal(id, id, author, genre, weight = weight)

    @Test fun `separate interests beat unrelated source bestseller`() {
        val result = LocalRecommendationRanker().rank(
            listOf(book("history", "історія"), book("fantasy", "фентезі"), book("cook", "кулінарія")),
            listOf(signal("h", "історія"), signal("f", "фентезі")),
            popularityByWorkId = mapOf("cook" to 1.0), topN = 2)
        assertEquals(setOf("history", "fantasy"), result.map { it.candidate.id }.toSet())
        assertTrue(result.all { it.reasonTitle in setOf("h", "f") })
    }

    @Test fun `explicit dislike beats completion favorite and progress`() {
        val signal = RecommendationPersonalization.signalsFor(listOf(
            RecommendationPersonalization.WorkBehavior("bad", "bad", rating = 1,
                isFavorite = true, completed = true, relistened = true, progressFraction = .9)
        ), 0).single()
        assertTrue(signal.weight < 0)
    }

    @Test fun `negative taste vetoes even popular exploration`() {
        val result = LocalRecommendationRanker().rank(
            listOf(book("horror", "жахи"), book("history", "історія")),
            listOf(signal("bad", "жахи", weight = -1.2)),
            popularityByWorkId = mapOf("horror" to 1.0), sourceLabelsByWorkId = mapOf("horror" to "source"))
        assertEquals(listOf("history"), result.map { it.candidate.id })
    }

    @Test fun `known hidden author and duplicates never return`() {
        val candidate = book("ok")
        val result = LocalRecommendationRanker().rank(
            listOf(book("known"), book("hidden", author = "  ІВАН! "), candidate, candidate),
            emptyList(), excludedWorkIds = setOf("known"), excludedAuthors = setOf("Іван"))
        assertEquals(listOf("ok"), result.map { it.candidate.id })
    }

    @Test fun `cold start rotates daily but is stable within day and caps authors and series`() {
        val candidates = (1..60).map { book("$it", author = "author${it / 3}", series = "series${it / 2}") }
        val engine = LocalRecommendationRanker()
        val first = engine.rank(candidates, emptyList(), nowEpochMs = 86_400_000)
        assertEquals(first, engine.rank(candidates.reversed(), emptyList(), nowEpochMs = 86_400_001))
        assertNotEquals(first.map { it.candidate.id }, engine.rank(candidates, emptyList(), nowEpochMs = 172_800_000).map { it.candidate.id })
        assertEquals(10, first.size)
        assertTrue(first.groupBy { it.candidate.author }.values.all { it.size <= 2 })
        assertEquals(first.size, first.distinctBy { it.candidate.series }.size)
        assertTrue(first.all { it.isExploration && it.reasonTitle.isEmpty() })
    }

    @Test fun `rare description terms retrieve relevant work and corpus updates invalidate index`() {
        val seed = book("seed", description = "археолог експедиція руїни")
        val match = book("match", description = "археолог експедиція руїни")
        val engine = LocalRecommendationRanker()
        val result = engine.rank(listOf(seed, match, book("noise", description = "рецепти випічка кухня")), listOf(signal("seed")), topN = 1)
        assertEquals("match", result.single().candidate.id)
        val updated = engine.rank(listOf(seed, book("new", description = "археолог експедиція руїни")), listOf(signal("seed")), topN = 1)
        assertEquals("new", updated.single().candidate.id)
    }

    @Test fun `progress tier boundaries refresh recommendations`() {
        val base = RecommendationRefreshPolicy.WorkSignal("work", false, true, false)
        assertNotEquals(RecommendationRefreshPolicy.libraryKey(listOf(base)),
            RecommendationRefreshPolicy.libraryKey(listOf(base.copy(progressTier = 1))))
        assertNotEquals(RecommendationRefreshPolicy.libraryKey(listOf(base.copy(progressTier = 1))),
            RecommendationRefreshPolicy.libraryKey(listOf(base.copy(progressTier = 2))))
    }

    @Test fun `ten thousand works do not require warmup or model`() {
        val candidates = (1..10_000).map { book("$it", genre = if (it % 2 == 0) "історія" else "фентезі") }
        val engine = LocalRecommendationRanker()
        val start = System.nanoTime()
        val result = engine.rank(candidates, listOf(signal("seed", "історія")))
        println("Local rank 10000 works: ${(System.nanoTime() - start) / 1_000_000} ms (host JVM, not a phone)")
        assertEquals(10, result.size)
        assertTrue(result.take(8).all { it.candidate.genre == "історія" })
    }
}
