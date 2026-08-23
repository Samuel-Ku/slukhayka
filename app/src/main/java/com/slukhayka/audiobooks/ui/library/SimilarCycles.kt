package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.collections.CollectionMatcher

/**
 * Spec-39 T2 (#262) — one «схожі цикли» card: the same landscape form as the
 * «Ваші цикли» card, but the magnet line is the engine's reason chip
 * («схоже на X») instead of a progress count.
 */
data class SimilarCycle(
    val title: String,
    val url: String,
    val coverImageUrl: String?,
    val reasonTitle: String
)

/**
 * Spec-39 T2 (#262) — the pure builder behind the «схожі цикли» tier of the
 * «Ваші цикли» shelf. Pure JVM: no Android, no I/O, no Compose.
 *
 * Input is exactly what Огляд already holds:
 *  - [picks] — the on-device recommendation engine's top picks (each carries
 *    the human reason chip);
 *  - [works] — every locally known Work (the catalogue sync keeps series
 *    identity on the Work row); the candidate id IS the Work id;
 *  - [ownCycleTitles] — the listener's own cycle titles («Ваші цикли»), so
 *    an owned cycle is never recommended back.
 *
 * Rules:
 *  - **Serial identity** — a pick becomes a cycle only when its Work carries
 *    a series title AND an openable series URL (a dead card is worse than an
 *    absent one, spec-39 Р4);
 *  - **Exclusion** — cycles the listener already owns never surface;
 *  - **Dedup** — several picks inside one cycle collapse to one card, the
 *    first (highest-ranked) pick wins, keeping its reason chip;
 *  - **Cap** — [SHELF_LIMIT] cards total;
 *  - **Best-effort** — no picks or no serial identity → an empty list, the
 *    shelf simply shows nothing extra (no spinners, no error states).
 */
object SimilarCycles {

    /** Hard cap of the tier (same doctrine as [PersonalCycles.SHELF_LIMIT]). */
    const val SHELF_LIMIT = 15

    fun build(
        picks: List<RecommendationEngine.Recommendation>,
        works: List<WorkEntity>,
        ownCycleTitles: Collection<String>
    ): List<SimilarCycle> {
        if (picks.isEmpty()) return emptyList()
        val workById = works.associateBy { it.id }
        val ownNormalized = ownCycleTitles.mapNotNull { CollectionMatcher.normalizeTitle(it) }.toSet()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SimilarCycle>()
        for (pick in picks) {
            if (result.size >= SHELF_LIMIT) break
            val work = workById[pick.candidate.id] ?: continue
            val seriesUrl = work.seriesUrl?.takeIf { it.isNotBlank() } ?: continue
            val title = work.seriesTitle?.takeIf { it.isNotBlank() } ?: continue
            val normalized = CollectionMatcher.normalizeTitle(title).takeIf { it.isNotBlank() } ?: continue
            if (normalized in ownNormalized) continue
            if (!seen.add(normalized)) continue
            result += SimilarCycle(
                title = title,
                url = seriesUrl,
                coverImageUrl = null,
                reasonTitle = pick.reasonTitle
            )
        }
        return result
    }
}
