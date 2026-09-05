package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationPersonalizationTest {
    private val day = 86_400_000L

    @Test
    fun `strongest progress tier combines with durable signals and clamps per work`() {
        val signals = RecommendationPersonalization.signalsFor(
            listOf(
                RecommendationPersonalization.WorkBehavior(
                    workId = "work",
                    title = "Книга",
                    isFavorite = true,
                    rating = 5,
                    progressFraction = .8,
                    progressRecordedAt = 200 * day,
                    completed = true,
                    relistened = true
                )
            ),
            nowEpochMs = 200 * day
        )

        assertEquals(1, signals.size)
        assertEquals(1.5, signals.single().weight, 0.0)
    }

    @Test
    fun `weak progress stays for thirty days then decays to zero by day 180`() {
        fun weight(ageDays: Long) = RecommendationPersonalization.signalsFor(
            listOf(
                RecommendationPersonalization.WorkBehavior(
                    workId = "work",
                    title = "Книга",
                    progressFraction = .7,
                    progressRecordedAt = (200 - ageDays) * day
                )
            ),
            nowEpochMs = 200 * day
        ).singleOrNull()?.weight ?: 0.0

        assertEquals(.5, weight(30), 1e-9)
        assertEquals(.25, weight(105), 1e-9)
        assertEquals(0.0, weight(180), 1e-9)
    }

    @Test
    fun `negative ratings create negative signals and rating three is neutral`() {
        val signals = RecommendationPersonalization.signalsFor(
            listOf(
                RecommendationPersonalization.WorkBehavior("one", "Один", rating = 1),
                RecommendationPersonalization.WorkBehavior("two", "Два", rating = 2),
                RecommendationPersonalization.WorkBehavior("three", "Три", rating = 3)
            ),
            nowEpochMs = 0
        )

        assertEquals(listOf(-1.2, -.8), signals.map { it.weight })
    }

    @Test
    fun `multiple editions collapse to the strongest work signal`() {
        val signals = RecommendationPersonalization.signalsFor(
            listOf(
                RecommendationPersonalization.WorkBehavior("work", "Edition 1", progressFraction = .3),
                RecommendationPersonalization.WorkBehavior("work", "Edition 2", isFavorite = true)
            ),
            nowEpochMs = 0
        )

        assertEquals(1, signals.size)
        assertEquals(1.0, signals.single().weight, 0.0)
        assertEquals("Edition 2", signals.single().title)
    }

    @Test
    fun `ranking subtracts negative centroid filters exclusions and applies diversity caps`() {
        val candidates = listOf(
            candidate("a1", "Автор A 1", "A", "S1"),
            candidate("a2", "Автор A 2", "A", "S2"),
            candidate("a3", "Автор A 3", "A", "S3"),
            candidate("b1", "Автор B 1", "B", "SB"),
            candidate("b2", "Автор B 2", "B", "SB"),
            candidate("hidden", "Прихована", "C", "SC")
        )
        val positive = RecommendationEngine.Signal("positive", "Улюблена", author = "A", weight = 1.0)
        val negative = RecommendationEngine.Signal("negative", "Не люблю", author = "B", weight = -1.0)
        val vectors = buildMap {
            put("positive", floatArrayOf(1f, 0f))
            put("negative", floatArrayOf(0f, 1f))
            candidates.forEachIndexed { index, item ->
                put(item.id, if (item.author == "B") floatArrayOf(.8f, .6f) else floatArrayOf(1f, index * .01f))
            }
        }

        val ranked = RecommendationPersonalization.rank(
            candidates = candidates,
            signals = listOf(positive, negative),
            vectors = vectors,
            excludedWorkIds = setOf("hidden"),
            topN = 10,
            explorationCount = 0
        )

        assertFalse(ranked.any { it.candidate.id == "hidden" })
        assertTrue(ranked.count { it.candidate.author == "A" } <= 2)
        assertTrue(ranked.count { it.candidate.series == "SB" } <= 1)
        assertTrue(ranked.first().candidate.author == "A")
    }

    @Test
    fun `ten item shelf reserves two deterministic positive-semantic exploration slots`() {
        val candidates = (1..15).map { candidate("c$it", "Книга $it", "Автор $it", "S$it") }
        val signal = RecommendationEngine.Signal("liked", "Улюблена", weight = 1.0)
        val vectors = buildMap {
            put("liked", floatArrayOf(1f, 0f))
            candidates.forEachIndexed { index, item -> put(item.id, floatArrayOf(1f, index / 100f)) }
        }

        val ranked = RecommendationPersonalization.rank(
            candidates = candidates,
            signals = listOf(signal),
            vectors = vectors,
            topN = 10,
            explorationCount = 2
        )

        assertEquals(10, ranked.size)
        assertEquals(2, ranked.count { it.isExploration })
        assertTrue(ranked.filter { it.isExploration }.all { it.semanticScore > 0.0 })
    }

    // --- #486: the small source-popularity component ------------------------

    @Test
    fun `default weights give popularity a small share and freshness loses part of its own`() {
        val weights = RecommendationPersonalization.ScoreWeights()
        assertEquals(.10, weights.popularity, 1e-9)
        assertTrue("freshness must give up part of its weight", weights.freshness < .10)
    }

    @Test
    fun `popularity lifts an equal-vector candidate only by the small capped component`() {
        val candidates = listOf(
            candidate("plain", "Звичайна книга", "Автор A", "S1"),
            candidate("popular", "Народна книга", "Автор B", "S2")
        )
        val signal = RecommendationEngine.Signal("liked", "Улюблена", weight = 1.0)
        val vectors = mapOf(
            "liked" to floatArrayOf(1f, 0f),
            "plain" to floatArrayOf(1f, 0f),
            "popular" to floatArrayOf(1f, 0f)
        )

        val ranked = RecommendationPersonalization.rank(
            candidates = candidates,
            signals = listOf(signal),
            vectors = vectors,
            popularityByWorkId = mapOf("popular" to 1.0),
            topN = 10,
            explorationCount = 0
        )

        assertEquals("popular", ranked.first().candidate.id)
        // The component can never twist the profile: the whole gap between two
        // otherwise-equal candidates is at most the one popularity weight.
        val gap = ranked[0].score - ranked[1].score
        assertTrue("gap $gap must be within the popularity weight", gap <= RecommendationPersonalization.ScoreWeights().popularity + 1e-9)
        // Semantic similarity itself is untouched by popularity.
        assertEquals(ranked[0].semanticScore, ranked[1].semanticScore, 1e-9)
    }

    @Test
    fun `rank and rating collapse into one normalized component`() {
        // Rank 1 in a source top = the component's ceiling.
        assertEquals(1.0, RecommendationPersonalization.normalizedPopularity(rank = 1, rating = null), 1e-9)
        // Lower positions taper toward zero.
        assertTrue(
            RecommendationPersonalization.normalizedPopularity(1, null) >
                RecommendationPersonalization.normalizedPopularity(7, null)
        )
        // Positions beyond the top-10 window contribute nothing on their own.
        assertEquals(0.0, RecommendationPersonalization.normalizedPopularity(rank = 40, rating = null), 1e-9)
        // A claimed rating scales on its own scale (1..5).
        assertTrue(
            RecommendationPersonalization.normalizedPopularity(null, 4.8) >
                RecommendationPersonalization.normalizedPopularity(null, 3.2)
        )
        // The strongest of the two claims wins; nothing claimed = zero.
        assertEquals(
            RecommendationPersonalization.normalizedPopularity(rank = 1, rating = null),
            RecommendationPersonalization.normalizedPopularity(rank = 1, rating = 3.0),
            1e-9
        )
        assertEquals(0.0, RecommendationPersonalization.normalizedPopularity(rank = null, rating = null), 1e-9)
    }

    @Test
    fun `popularity alone never ranks - personal signals stay the gate`() {
        val candidates = listOf(candidate("p1", "Народна книга", "Автор", "S"))
        val ranked = RecommendationPersonalization.rank(
            candidates = candidates,
            signals = emptyList(),
            vectors = mapOf("p1" to floatArrayOf(1f, 0f)),
            popularityByWorkId = mapOf("p1" to 1.0),
            topN = 10,
            explorationCount = 0
        )
        assertTrue(ranked.isEmpty())
    }

    // --- #486: «джерело радить» exploration slots ---------------------------

    @Test
    fun `two exploration slots become source-suggested picks with per-source badges`() {
        // Ten candidates aligned with the listener's profile, plus two books
        // the sources' tops celebrate but the profile says nothing about.
        val personal = (1..10).map { candidate("p$it", "Книга $it", "Автор $it", "S$it") }
        val topped = listOf(
            candidate("top1", "Топ один", "Інший автор 1", "X1"),
            candidate("top2", "Топ два", "Інший автор 2", "X2")
        )
        val signal = RecommendationEngine.Signal("liked", "Улюблена", weight = 1.0)
        val vectors = buildMap {
            put("liked", floatArrayOf(1f, 0f))
            personal.forEachIndexed { index, item -> put(item.id, floatArrayOf(1f, index / 100f)) }
            topped.forEachIndexed { index, item -> put(item.id, floatArrayOf(0f, 1f + index / 100f)) }
        }

        val ranked = RecommendationPersonalization.rank(
            candidates = personal + topped,
            signals = listOf(signal),
            vectors = vectors,
            popularityByWorkId = mapOf("top1" to 1.0, "top2" to 0.9),
            sourceLabelsByWorkId = mapOf("top1" to "sound-books", "top2" to "sluhay"),
            topN = 10,
            explorationCount = 2
        )

        assertEquals(10, ranked.size)
        val explored = ranked.filter { it.isExploration }
        assertEquals(listOf("top1", "top2"), explored.map { it.candidate.id })
        assertEquals(listOf("sound-books", "sluhay"), explored.map { it.sourceLabel })
        // Personal slots keep the reason chip and never carry a source badge.
        val personalPicks = ranked.filter { !it.isExploration }
        assertTrue(personalPicks.all { it.reasonTitle.isNotBlank() })
        assertTrue(personalPicks.all { it.sourceLabel == null })
    }

    @Test
    fun `a source top never suggests a book the profile pushes against`() {
        // top1 sits at the TOP of a source list but is close to the listener's
        // NEGATIVE centroid — the source's celebration must not surface it.
        val personal = (1..10).map { candidate("p$it", "Книга $it", "Автор $it", "S$it") }
        val topped = listOf(
            candidate("top1", "Топ один", "Інший автор 1", "X1"),
            candidate("top2", "Топ два", "Інший автор 2", "X2")
        )
        val vectors = buildMap {
            put("liked", floatArrayOf(1f, 0f, 0f))
            put("disliked", floatArrayOf(0f, 1f, 0f))
            personal.forEachIndexed { index, item -> put(item.id, floatArrayOf(1f, 0f, index / 100f)) }
            put("top1", floatArrayOf(0.1f, 1f, 0f)) // negative-aligned
            put("top2", floatArrayOf(0f, 0f, 1f)) // orthogonal to both, source-only
        }

        val ranked = RecommendationPersonalization.rank(
            candidates = personal + topped,
            signals = listOf(
                RecommendationEngine.Signal("liked", "Улюблена", weight = 1.0),
                RecommendationEngine.Signal("disliked", "Не люблю", weight = -1.0)
            ),
            vectors = vectors,
            popularityByWorkId = mapOf("top1" to 1.0, "top2" to 0.9),
            sourceLabelsByWorkId = mapOf("top1" to "sound-books", "top2" to "sluhay"),
            topN = 10,
            explorationCount = 2
        )

        assertTrue(ranked.none { it.candidate.id == "top1" })
        // top2 takes a source slot; the second slot falls back to the
        // semantic pool (only one eligible source candidate remained).
        val explored = ranked.filter { it.isExploration }
        assertEquals(2, explored.size)
        val sourcePicks = explored.filter { it.sourceLabel != null }
        assertEquals(listOf("top2"), sourcePicks.map { it.candidate.id })
        assertEquals(listOf("sluhay"), sourcePicks.map { it.sourceLabel })
    }

    @Test
    fun `without source coverage the exploration slots stay semantic`() {
        val candidates = (1..15).map { candidate("c$it", "Книга $it", "Автор $it", "S$it") }
        val signal = RecommendationEngine.Signal("liked", "Улюблена", weight = 1.0)
        val vectors = buildMap {
            put("liked", floatArrayOf(1f, 0f))
            candidates.forEachIndexed { index, item -> put(item.id, floatArrayOf(1f, index / 100f)) }
        }

        val ranked = RecommendationPersonalization.rank(
            candidates = candidates,
            signals = listOf(signal),
            vectors = vectors,
            popularityByWorkId = emptyMap(),
            sourceLabelsByWorkId = emptyMap(),
            topN = 10,
            explorationCount = 2
        )

        val explored = ranked.filter { it.isExploration }
        assertEquals(2, explored.size)
        assertTrue(explored.all { it.sourceLabel == null })
        assertTrue(explored.all { it.semanticScore > 0.0 })
    }

    private fun candidate(id: String, title: String, author: String, series: String) =
        RecommendationEngine.Candidate(id = id, title = title, author = author, series = series)
}
