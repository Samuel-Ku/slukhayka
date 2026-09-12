package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.db.WorkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #732 / ADR-0041 — the recommendation pool is the local Mirror, including
 * Works the listener has not imported yet; the card id is the Work key the
 * ordinary coordinator resolves on tap.
 */
class RecommendationCandidatesTest {

    private fun work(
        id: String,
        mergeKey: String,
        title: String,
        author: String = "Автор",
        seriesTitle: String? = null,
        coverImageUrl: String? = null
    ) = WorkEntity(
        id = id,
        mergeKey = mergeKey,
        title = title,
        author = author,
        seriesTitle = seriesTitle,
        coverImageUrl = coverImageUrl,
        addedAt = 0L
    )

    @Test
    fun `pool covers owned and not-yet-imported mirror works alike`() {
        val candidates = recommendationCandidates(
            listOf(
                work("w-owned", "кобзар|шевченко", "Кобзар"),
                work(
                    "w-mirror",
                    "гіперіон|сімонс",
                    "Гіперіон",
                    seriesTitle = "Гіперіон",
                    coverImageUrl = "https://cdn/hyperion.jpg"
                )
            )
        )

        assertEquals(listOf("кобзар|шевченко", "гіперіон|сімонс"), candidates.map { it.id })
        assertEquals("Гіперіон", candidates[1].series)
        assertEquals("https://cdn/hyperion.jpg", candidates[1].coverImageUrl)
    }

    @Test
    fun `a blank merge key falls back to the work id`() {
        val candidates = recommendationCandidates(listOf(work("source-42", "", "Без ключа")))

        assertEquals("source-42", candidates.single().id)
    }

    @Test
    fun `unknown series and cover stay absent, never invented`() {
        val candidate = recommendationCandidates(listOf(work("w1", "mk1", "Книга"))).single()

        assertNull(candidate.coverImageUrl)
        assertEquals("", candidate.series)
    }
}
