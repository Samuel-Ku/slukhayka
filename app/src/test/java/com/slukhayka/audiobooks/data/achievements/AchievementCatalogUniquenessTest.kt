package com.slukhayka.audiobooks.data.achievements

import org.junit.Assert.assertEquals
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
 */
class AchievementCatalogUniquenessTest {

    @Test fun `only the two spec-mandated pairs share a metric and a threshold`() {
        val shared = AchievementCatalog.definitions
            .groupBy { it.metric to it.threshold }
            .filterValues { it.size > 1 }
            .mapValues { (_, definitions) -> definitions.map { it.id }.sorted() }

        assertEquals(
            mapOf(
                (AchievementMetric.COMPLETED_BOOKS to 1L) to listOf("books_1", "first_completion"),
                (AchievementMetric.SEARCH_IMPORTS to 1L) to listOf("deep_search", "first_search_import")
            ),
            shared
        )
    }

    @Test fun `every award id appears once`() {
        val ids = AchievementCatalog.definitions.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
