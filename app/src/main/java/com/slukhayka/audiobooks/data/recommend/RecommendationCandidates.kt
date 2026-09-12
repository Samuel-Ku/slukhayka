package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.db.WorkEntity

/**
 * #732 / ADR-0041 — the recommendation row's candidate pool is the Mirror:
 * the local Works, including ones the listener has NOT imported. The row is
 * therefore stable offline and needs no ephemeral union refresh, and the card
 * id is the Work key the ordinary card coordinator resolves on tap (importing
 * through the real doors).
 *
 * Pure JVM so the pool composition is unit-testable without a ViewModel.
 */
fun recommendationCandidates(works: List<WorkEntity>): List<RecommendationEngine.Candidate> =
    works.map { work ->
        RecommendationEngine.Candidate(
            id = recommendationWorkKey(work),
            title = work.title,
            author = work.author,
            series = work.seriesTitle.orEmpty(),
            coverImageUrl = work.coverImageUrl
        )
    }

/** The stable Work identity the recommendation row keys on. */
fun recommendationWorkKey(work: WorkEntity): String = work.mergeKey.ifBlank { work.id }
