package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkFacts

/**
 * #732 / ADR-0041 — the recommendation row's candidate pool is the Mirror:
 * the local Works, including ones the listener has NOT imported. The row is
 * therefore stable offline and needs no ephemeral union refresh, and the card
 * id is the Work key the ordinary card coordinator resolves on tap (importing
 * through the real doors).
 *
 * Pure JVM so the pool composition is unit-testable without a ViewModel.
 */
fun recommendationCandidates(
    works: List<WorkEntity>,
    /**
     * #484 — the genre/description already known per Work (one bulk read).
     * A missing fact is honestly absent, never fetched per candidate.
     */
    facts: Map<String, WorkFacts> = emptyMap()
): List<RecommendationEngine.Candidate> =
    works.map { work ->
        val key = recommendationWorkKey(work)
        val fact = facts[key]
        RecommendationEngine.Candidate(
            id = key,
            title = work.title,
            author = work.author,
            genre = fact?.genre.orEmpty(),
            series = work.seriesTitle.orEmpty(),
            description = fact?.description.orEmpty(),
            coverImageUrl = work.coverImageUrl
        )
    }

/** The stable Work identity the recommendation row keys on. */
fun recommendationWorkKey(work: WorkEntity): String = work.mergeKey.ifBlank { work.id }
