package com.slukhayka.audiobooks.data.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1166 (T8) — the catalogue must not hand out two notices for ONE fact.
 *
 * The rule already lives in the catalogue as a decision: «Ювілей години» is
 * deliberately absent because `hours_100` sits on the same metric at the same
 * threshold, and a duplicate is not a second achievement. Nothing enforced it
 * catalogue-wide, so this test does: a NEW pair that shares a metric and a
 * threshold fails here and has to be argued for.
 *
 * Two pairs DO share both today, and both are asked for by the spec itself:
 * `first_completion` / `books_1` (US8 first finished book, US18 the ladder
 * starting at one) and `first_search_import` / `deep_search` (US5 first search
 * import, US43 the global-search mechanism). They are pinned, not blessed:
 * changing that is a product decision, and this test is where it starts.
 *
 * The key is (metric, threshold, genre) and not (metric, threshold), because
 * that is the key the evaluator itself reads — `value(snapshot, genreId)`.
 * #702 (T4, зріз 2) added ten NAMED «10 книг у жанрі» shelves on ONE metric at
 * ONE threshold; each names a different genre, so they watch ten different
 * facts and are not duplicates. Without the genre in the key those ten would
 * read as a collision and this guard would have to be weakened instead of
 * sharpened.
 */
class AchievementCatalogUniquenessTest {

    @Test fun `only the two spec-mandated pairs share a metric, a threshold and a genre`() {
        val shared = AchievementCatalog.definitions
            .groupBy { Triple(it.metric, it.threshold, it.genreId) }
            .filterValues { it.size > 1 }
            .mapValues { (_, definitions) -> definitions.map { it.id }.sorted() }

        assertEquals(
            mapOf(
                Triple(AchievementMetric.COMPLETED_BOOKS, 1L, null) to
                    listOf("books_1", "first_completion"),
                Triple(AchievementMetric.SEARCH_IMPORTS, 1L, null) to
                    listOf("deep_search", "first_search_import")
            ),
            shared
        )
    }

    /**
     * #702 (T4, зріз 2) — the family that made the genre part of the key. Ten
     * shelves, one metric and one threshold by design, ten DISTINCT genres: two
     * shelves for the same genre would be the real duplicate this guard hunts,
     * and one shelf drifting onto its own metric would hide that.
     */
    @Test fun `the named genre shelves are one metric and threshold with ten distinct genres`() {
        val shelves = AchievementCatalog.definitions.filter { it.genreId != null }

        assertTrue("жанрові полиці мусять бути в каталозі", shelves.isNotEmpty())
        assertEquals(
            "усі полиці мають ділити одну пару метрики й порога — саме це й оправдовує ключ із жанром",
            1,
            shelves.map { it.metric to it.threshold }.distinct().size
        )
        assertEquals(
            "жанр не має повторюватись — дві полиці на один жанр були б справжнім дублем",
            shelves.size,
            shelves.map { it.genreId }.distinct().size
        )
    }

    @Test fun `every award id appears once`() {
        val ids = AchievementCatalog.definitions.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
