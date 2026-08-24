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

    private fun candidate(id: String, title: String, author: String, series: String) =
        RecommendationEngine.Candidate(id = id, title = title, author = author, series = series)
}
