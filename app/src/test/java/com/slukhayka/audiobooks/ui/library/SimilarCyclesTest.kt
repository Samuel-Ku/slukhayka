package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec-39 T2 (#262) — the «схожі цикли» pure builder rules. */
class SimilarCyclesTest {

    private fun work(
        id: String,
        title: String,
        seriesTitle: String? = null,
        seriesUrl: String? = null
    ) = WorkEntity(
        id = id,
        mergeKey = "$title|author",
        title = title,
        author = "Автор",
        seriesTitle = seriesTitle,
        seriesUrl = seriesUrl
    )

    private fun pick(id: String, reason: String, score: Double = 1.0) =
        RecommendationEngine.Recommendation(
            candidate = RecommendationEngine.Candidate(id = id, title = "Книга $id"),
            score = score,
            reasonTitle = reason
        )

    @Test
    fun `picks lift to cycles through the Work's series identity`() {
        val cycles = SimilarCycles.build(
            picks = listOf(pick("w1", "Відьмак: Останнє бажання")),
            ownCycleTitles = emptyList(),
            works = listOf(work("w1", "Останнє бажання", "Відьмак", "/series/vidmak/"))
        )
        assertEquals(1, cycles.size)
        assertEquals("Відьмак", cycles[0].title)
        assertEquals("/series/vidmak/", cycles[0].url)
        assertEquals("Відьмак: Останнє бажання", cycles[0].reasonTitle)
    }

    @Test
    fun `own cycles never surface in the similar tier`() {
        val cycles = SimilarCycles.build(
            picks = listOf(pick("w1", "reason")),
            works = listOf(work("w1", "Книга", "Відьмак", "/s/")),
            ownCycleTitles = listOf("Відьмак (цикл)")
        )
        assertTrue(cycles.isEmpty())
    }

    @Test
    fun `several picks of one cycle dedup keeping the first reason`() {
        val cycles = SimilarCycles.build(
            picks = listOf(
                pick("w1", "Перша причина"),
                pick("w2", "Друга причина")
            ),
            ownCycleTitles = emptyList(),
            works = listOf(
                work("w1", "Книга 1", "Відьмак", "/s/"),
                work("w2", "Книга 2", "Відьмак", "/s/")
            )
        )
        assertEquals(1, cycles.size)
        assertEquals("Перша причина", cycles[0].reasonTitle)
    }

    @Test
    fun `a pick without serial identity or openable url is skipped`() {
        val cycles = SimilarCycles.build(
            picks = listOf(pick("w1", "r1"), pick("w2", "r2"), pick("w3", "r3")),
            ownCycleTitles = emptyList(),
            works = listOf(
                work("w1", "Без циклу"),
                work("w2", "Цикл без URL", "Назва", null),
                work("w3", "Повний", "Назва2", "/s2/")
            )
        )
        assertEquals(listOf("Назва2"), cycles.map { it.title })
    }

    @Test
    fun `no picks yield an empty tier`() {
        assertTrue(SimilarCycles.build(emptyList(), listOf(work("w1", "x")), emptyList()).isEmpty())
    }
}
