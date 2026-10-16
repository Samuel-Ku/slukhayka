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
    fun `ranking subtracts negative interest filters exclusions and applies diversity caps`() {
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
        // negative interest — the source's celebration must not surface it.
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
    @Test
    fun `a blocked author never enters the row`() {
        val candidates = listOf(
            candidate("k1", "Кобзар", "Тарас Шевченко", "Поезія"),
            candidate("k2", "Гайдамаки", "Тарас Шевченко", "Поезія"),
            candidate("m1", "Гіперіон", "Ден Сімонс", "Фантастика")
        )
        val signal = RecommendationEngine.Signal("s1", "Улюблена", author = "Ден Сімонс", weight = 1.0)
        val vectors = buildMap {
            put("s1", floatArrayOf(1f, 0f))
            candidates.forEachIndexed { index, item ->
                put(item.id, floatArrayOf(1f, index * .01f))
            }
        }

        val ranked = RecommendationPersonalization.rank(
            candidates = candidates,
            signals = listOf(signal),
            vectors = vectors,
            excludedAuthors = setOf("Тарас Шевченко"),
            topN = 10,
            explorationCount = 0
        )

        assertTrue(ranked.isNotEmpty())
        assertFalse(ranked.any { it.candidate.author == "Тарас Шевченко" })
    }
    @Test
    fun `separate interests put each matching book before a bridge with its own reason`() {
        val ranked = RecommendationPersonalization.rank(
            candidates = listOf("01-A", "01-B", "01-Bridge").map { RecommendationEngine.Candidate(it, it) },
            signals = listOf(
                RecommendationEngine.Signal("P1", "P1", weight = 1.0),
                RecommendationEngine.Signal("P2", "P2", weight = 1.0)
            ),
            vectors = mapOf(
                "P1" to floatArrayOf(1f, 0f), "P2" to floatArrayOf(0f, 1f),
                "01-A" to floatArrayOf(1f, 0f), "01-B" to floatArrayOf(0f, 1f),
                "01-Bridge" to floatArrayOf(.70710677f, .70710677f)
            ),
            topN = 3, explorationCount = 0
        )
        assertEquals(listOf("01-A", "01-B", "01-Bridge"), ranked.map { it.candidate.id })
        assertEquals(listOf("P1", "P2", "P1"), ranked.map { it.reasonTitle })
    }

    @Test
    fun `equal positive overlap ranks smaller negative overlap first`() {
        val ranked = RecommendationPersonalization.rank(
            candidates = listOf("02-X", "02-Y").map { RecommendationEngine.Candidate(it, it) },
            signals = listOf(RecommendationEngine.Signal("P", "P", weight = 1.0), RecommendationEngine.Signal("N", "N", weight = -1.0)),
            vectors = mapOf("P" to floatArrayOf(1f, 0f), "N" to floatArrayOf(.8f, .6f),
                "02-X" to floatArrayOf(.8f, .6f), "02-Y" to floatArrayOf(.8f, -.6f)),
            topN = 2, explorationCount = 0
        )
        assertEquals(listOf("02-Y", "02-X"), ranked.map { it.candidate.id })
        assertEquals(.604, ranked[0].semanticScore, 1e-6)
        assertEquals(.1, ranked[1].semanticScore, 1e-6)
    }

    @Test
    fun `metadata lifts use actual genre aliases without inferring unknown genres`() {
        val ranked = RecommendationPersonalization.rank(
            candidates = listOf(
                RecommendationEngine.Candidate("03-author", "Author", author = "Positive Author"),
                RecommendationEngine.Candidate("03-genre", "Genre", genre = "sci-fi"),
                RecommendationEngine.Candidate("03-series", "Series", series = "Positive Series"),
                RecommendationEngine.Candidate("03-none", "None"),
                RecommendationEngine.Candidate("03-space", "Space", genre = "Space Tales")
            ),
            signals = listOf(RecommendationEngine.Signal("P", "P", author = "Positive Author", genre = "Science Fiction", series = "Positive Series", weight = 1.0)),
            vectors = listOf("P", "03-author", "03-genre", "03-series", "03-none", "03-space").associateWith { floatArrayOf(1f, 0f) },
            topN = 5, explorationCount = 0
        )
        assertEquals(listOf("03-author", "03-genre", "03-series", "03-none", "03-space"), ranked.map { it.candidate.id })
        listOf(.70, .65, .60, .55, .55).zip(ranked).forEach { (expected, item) -> assertEquals(expected, item.score, 1e-9) }
    }

    @Test
    fun `negative metadata subtracts facets without inventing positive facets`() {
        val ranked = RecommendationPersonalization.rank(
            candidates = listOf(
                RecommendationEngine.Candidate("04-X", "X", author = "Negative Author", genre = "poetry", series = "Negative Series"),
                RecommendationEngine.Candidate("04-Y", "Y")
            ),
            signals = listOf(
                RecommendationEngine.Signal("P", "P", weight = 1.0),
                RecommendationEngine.Signal("N", "N", author = "Negative Author", genre = "poetry", series = "Negative Series", weight = -1.0)
            ),
            vectors = mapOf("P" to floatArrayOf(1f, 0f), "N" to floatArrayOf(0f, 1f), "04-X" to floatArrayOf(1f, 0f), "04-Y" to floatArrayOf(1f, 0f)),
            topN = 2, explorationCount = 0
        )
        assertEquals(listOf("04-Y", "04-X"), ranked.map { it.candidate.id })
        assertEquals(1.0, ranked[0].semanticScore, 1e-9)
        assertEquals(1.0, ranked[1].semanticScore, 1e-9)
        assertEquals(.55, ranked[0].score, 1e-9)
        assertEquals(.25, ranked[1].score, 1e-9)
    }

    @Test
    fun `hard exclusions precede unchanged greedy author and series caps`() {
        val candidates = listOf(
            RecommendationEngine.Candidate("05-A1", "A1", author = "Author A", series = "S1"),
            RecommendationEngine.Candidate("05-C", "C", author = "Author C", series = "S1"),
            RecommendationEngine.Candidate("05-A2", "A2", author = "Author A", series = "S2"),
            RecommendationEngine.Candidate("05-A3", "A3", author = "Author A", series = "S3"),
            RecommendationEngine.Candidate("05-B", "B", author = "Author B", series = "S4"),
            RecommendationEngine.Candidate("05-H", "H", author = "Hidden Author"),
            RecommendationEngine.Candidate("05-K", "K")
        )
        val cosines = mapOf("05-A1" to .95, "05-C" to .90, "05-A2" to .85, "05-A3" to .80, "05-B" to .75, "05-H" to .99, "05-K" to .98)
        val ranked = RecommendationPersonalization.rank(
            candidates, listOf(RecommendationEngine.Signal("P", "P", weight = 1.0)),
            cosines.mapValues { (_, x) -> floatArrayOf(x.toFloat(), kotlin.math.sqrt(1 - x * x).toFloat()) } + ("P" to floatArrayOf(1f, 0f)),
            excludedWorkIds = setOf("05-K"), excludedAuthors = setOf("Hidden Author"), topN = 3, explorationCount = 0
        )
        assertEquals(listOf("05-A1", "05-A2", "05-B"), ranked.map { it.candidate.id })
    }

    @Test
    fun `missing vectors are skipped and negative only evidence has no personal row`() {
        val candidates = listOf("06-V1", "06-V2", "06-M").map { RecommendationEngine.Candidate(it, it) }
        val vectors = mapOf("P" to floatArrayOf(1f, 0f), "N" to floatArrayOf(0f, 1f), "06-V1" to floatArrayOf(1f, 0f), "06-V2" to floatArrayOf(1f, 0f))
        val ranked = RecommendationPersonalization.rank(candidates,
            listOf(RecommendationEngine.Signal("P", "P", weight = 1.0)), vectors, topN = 3, explorationCount = 0)
        assertEquals(listOf("06-V1", "06-V2"), ranked.map { it.candidate.id })
        val negativeOnly = RecommendationPersonalization.rank(candidates,
            listOf(RecommendationEngine.Signal("N", "N", weight = -1.0)), vectors, topN = 3, explorationCount = 0)
        assertTrue(negativeOnly.isEmpty())
    }

    @Test
    fun `reason follows weighted interest strength instead of raw cosine`() {
        val ranked = RecommendationPersonalization.rank(
            candidates = listOf(RecommendationEngine.Candidate("C", "C")),
            signals = listOf(RecommendationEngine.Signal("Pweak", "Weak", weight = 1.0), RecommendationEngine.Signal("Pstrong", "Strong", weight = 2.0)),
            vectors = mapOf("C" to floatArrayOf(1f, 0f), "Pweak" to floatArrayOf(1f, 0f), "Pstrong" to floatArrayOf(.6f, .8f)),
            topN = 1, explorationCount = 0
        )
        assertEquals("Strong", ranked.single().reasonTitle)
        assertEquals(.6, ranked.single().semanticScore, 1e-6)
        assertEquals(.33, ranked.single().score, 1e-6)
    }

    @Test
    fun `equal weighted reasons use stable work ID regardless of signal order`() {
        val ranked = RecommendationPersonalization.rank(
            candidates = listOf(RecommendationEngine.Candidate("C", "C")),
            signals = listOf(RecommendationEngine.Signal("P02", "Second", weight = 2.0), RecommendationEngine.Signal("P01", "First", weight = 2.0)),
            vectors = mapOf("C" to floatArrayOf(1f, 0f), "P01" to floatArrayOf(.6f, .8f), "P02" to floatArrayOf(.6f, -.8f)),
            topN = 1, explorationCount = 0
        )
        assertEquals("First", ranked.single().reasonTitle)
        assertEquals(.6, ranked.single().semanticScore, 1e-6)
    }

    @Test
    fun `eligible negative strength participates in the shared denominator`() {
        val ranked = RecommendationPersonalization.rank(
            candidates = listOf(RecommendationEngine.Candidate("C", "C")),
            signals = listOf(RecommendationEngine.Signal("P", "P", weight = 1.0), RecommendationEngine.Signal("N", "N", weight = -2.0)),
            vectors = mapOf("C" to floatArrayOf(1f, 0f), "P" to floatArrayOf(1f, 0f), "N" to floatArrayOf(0f, 1f)),
            topN = 1, explorationCount = 0
        )
        assertEquals(.5, ranked.single().semanticScore, 1e-9)
        assertEquals(.275, ranked.single().score, 1e-9)
    }

    @Test
    fun `positive cap retains strongest twenty with stable IDs and omits their lost facets`() {
        val signals = (21 downTo 1).map { index ->
            val id = "P%02d".format(java.util.Locale.ROOT, index)
            RecommendationEngine.Signal(id, id, genre = if (index == 21) "poetry" else "", weight = 1.0)
        }
        val vectors = signals.associate { it.id to if (it.id == "P21") floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f) } +
            mapOf("A" to floatArrayOf(1f, 0f), "B" to floatArrayOf(0f, 1f))
        val ranked = RecommendationPersonalization.rank(
            listOf(RecommendationEngine.Candidate("A", "A", genre = "poetry"), RecommendationEngine.Candidate("B", "B")),
            signals, vectors, topN = 2, explorationCount = 0
        )
        assertEquals(listOf("A"), ranked.map { it.candidate.id })
        assertEquals(.55, ranked.single().score, 1e-9)
        assertEquals("P01", ranked.single().reasonTitle)
    }

    @Test
    fun `the twenty first negative still contributes semantic and metadata penalties`() {
        val negatives = (1..21).map { index ->
            val id = "N%02d".format(java.util.Locale.ROOT, index)
            RecommendationEngine.Signal(id, id, genre = if (index == 21) "poetry" else "", weight = -1.0)
        }
        val vectors = negatives.associate { it.id to if (it.id == "N21") floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) } +
            mapOf("P" to floatArrayOf(1f, 0f), "C" to floatArrayOf(1f, 0f))
        val ranked = RecommendationPersonalization.rank(
            listOf(RecommendationEngine.Candidate("C", "C", genre = "poetry")),
            listOf(RecommendationEngine.Signal("P", "P", weight = 1.0)) + negatives, vectors,
            topN = 1, explorationCount = 0
        )
        assertEquals(.30, ranked.single().semanticScore, 1e-9)
        assertEquals(.065, ranked.single().score, 1e-9)
    }

    @Test
    fun `antipodal negative evidence never becomes a semantic bonus`() {
        val ranked = RecommendationPersonalization.rank(
            listOf(RecommendationEngine.Candidate("C", "C")),
            listOf(RecommendationEngine.Signal("P", "P", weight = 1.0), RecommendationEngine.Signal("N", "N", weight = -1.0)),
            mapOf("P" to floatArrayOf(1f, 0f), "N" to floatArrayOf(-1f, 0f), "C" to floatArrayOf(1f, 0f)),
            topN = 1, explorationCount = 0
        )
        assertEquals(1.0, ranked.single().semanticScore, 1e-9)
        assertEquals(.55, ranked.single().score, 1e-9)
    }

    @Test
    fun `only finite nonzero signed supports with vectors affect rank and normalization`() {
        val invalid = listOf(
            RecommendationEngine.Signal("infinite-positive", "Invalid", genre = "poetry", weight = Double.POSITIVE_INFINITY),
            RecommendationEngine.Signal("infinite-negative", "Invalid", author = "Invalid", weight = Double.NEGATIVE_INFINITY),
            RecommendationEngine.Signal("nan", "Invalid", weight = Double.NaN),
            RecommendationEngine.Signal("zero", "Invalid", weight = 0.0),
            RecommendationEngine.Signal("missing", "Invalid", weight = 100.0)
        )
        val candidates = listOf(RecommendationEngine.Candidate("C", "C", author = "Invalid", genre = "poetry"), RecommendationEngine.Candidate("D", "D"))
        val vectors = mapOf("P" to floatArrayOf(1f, 0f), "C" to floatArrayOf(1f, 0f), "D" to floatArrayOf(0f, 1f),
            "infinite-positive" to floatArrayOf(0f, 1f), "infinite-negative" to floatArrayOf(1f, 0f), "nan" to floatArrayOf(1f, 0f), "zero" to floatArrayOf(0f, 1f))
        val ranked = RecommendationPersonalization.rank(candidates,
            listOf(RecommendationEngine.Signal("P", "P", weight = 1.0)) + invalid, vectors, topN = 2, explorationCount = 0)
        assertEquals(listOf("C"), ranked.map { it.candidate.id })
        assertEquals(1.0, ranked.single().semanticScore, 1e-9)
        assertEquals(.55, ranked.single().score, 1e-9)
        assertEquals("P", ranked.single().reasonTitle)
        assertTrue(RecommendationPersonalization.rank(candidates, invalid, vectors, topN = 2, explorationCount = 0).isEmpty())
    }

    @Test
    fun `shared binary facets cancel while warranted negative scores stay signed`() {
        val ranked = RecommendationPersonalization.rank(
            listOf(RecommendationEngine.Candidate("C", "C", author = "ÉLAN AUTHOR", genre = "sci-fi", series = "Saga One")),
            listOf(
                RecommendationEngine.Signal("P", "P", author = "Élan—Author", genre = "Science Fiction", series = "Saga—One", weight = 1.0),
                RecommendationEngine.Signal("N", "N", author = "élan author", genre = "sci-fi", series = "saga one", weight = -2.0)
            ),
            listOf("P", "N", "C").associateWith { floatArrayOf(1f, 0f) }, topN = 1, explorationCount = 0
        )
        assertEquals(listOf("C"), ranked.map { it.candidate.id })
        assertEquals(-.20, ranked.single().semanticScore, 1e-9)
        assertEquals(-.11, ranked.single().score, 1e-9)
        assertEquals("P", ranked.single().reasonTitle)
    }

    @Test
    fun `unknown genre matches its own raw identity without becoming science fiction`() {
        val ranked = RecommendationPersonalization.rank(
            listOf(RecommendationEngine.Candidate("Space", "Space", genre = "space tales"), RecommendationEngine.Candidate("SciFi", "SciFi", genre = "sci-fi")),
            listOf(RecommendationEngine.Signal("P", "P", genre = "Space Tales", weight = 1.0)),
            listOf("P", "Space", "SciFi").associateWith { floatArrayOf(1f, 0f) }, topN = 2, explorationCount = 0
        )
        assertEquals(listOf("Space", "SciFi"), ranked.map { it.candidate.id })
        assertEquals(.65, ranked[0].score, 1e-9)
        assertEquals(.55, ranked[1].score, 1e-9)
    }

    @Test
    fun `source eligible zero overlap keeps stable reason despite unequal strength ordering`() {
        val signals = listOf(RecommendationEngine.Signal("P02", "Second", weight = 2.0), RecommendationEngine.Signal("P01", "First", weight = 1.0))
        for (order in listOf(signals, signals.reversed())) {
            val ranked = RecommendationPersonalization.rank(
                listOf(RecommendationEngine.Candidate("C", "C")), order,
                mapOf("P01" to floatArrayOf(1f, 0f), "P02" to floatArrayOf(1f, 0f), "C" to floatArrayOf(0f, 1f)),
                topN = 1, explorationCount = 0, sourceLabelsByWorkId = mapOf("C" to "Source")
            )
            assertEquals("First", ranked.single().reasonTitle)
            assertEquals(0.0, ranked.single().semanticScore, 0.0)
            assertEquals(0.0, ranked.single().score, 0.0)
        }
    }

    @Test
    fun `positive cap prioritizes strength before stable ID and loses omitted metadata`() {
        val signals = (1..21).map { index ->
            val id = "P%02d".format(java.util.Locale.ROOT, index)
            RecommendationEngine.Signal(id, id, genre = if (index == 1) "poetry" else "", weight = if (index == 1) 1.0 else 2.0)
        }
        val vectors = signals.associate { it.id to if (it.id == "P01") floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f) } +
            mapOf("A" to floatArrayOf(1f, 0f), "B" to floatArrayOf(0f, 1f))
        val ranked = RecommendationPersonalization.rank(
            listOf(RecommendationEngine.Candidate("A", "A", genre = "poetry"), RecommendationEngine.Candidate("B", "B")),
            signals, vectors, topN = 2, explorationCount = 0
        )
        assertEquals(listOf("A"), ranked.map { it.candidate.id })
        assertEquals(.55, ranked.single().score, 1e-9)
        assertEquals("P02", ranked.single().reasonTitle)
    }

    @Test
    fun `source eligible antipodal positive overlap is zero rather than negative`() {
        val ranked = RecommendationPersonalization.rank(
            listOf(RecommendationEngine.Candidate("C", "C")),
            listOf(RecommendationEngine.Signal("P", "P", weight = 1.0)),
            mapOf("P" to floatArrayOf(1f, 0f), "C" to floatArrayOf(-1f, 0f)),
            topN = 1, explorationCount = 0, sourceLabelsByWorkId = mapOf("C" to "Source")
        )
        assertEquals(0.0, ranked.single().semanticScore, 0.0)
        assertEquals(0.0, ranked.single().score, 0.0)
        assertEquals("P", ranked.single().reasonTitle)
    }

}
