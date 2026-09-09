package com.slukhayka.audiobooks.data.watch

import com.slukhayka.audiobooks.data.source.GlobalSearchResult

/**
 * spec-49 T4 — the pure Source Watch appearance policy (ADR-0037 §6).
 *
 * A watched Work («Чекає на джерело») is satisfied by an **appearance**:
 * any non-refused source that starts carrying the Work. The policy decides
 * what counts as an appearance and how many times it fires; everything
 * else — persistence, the union scan timing, the notification itself —
 * lives outside.
 *
 * Inputs (all zero-request by contract):
 * - the union catalog the app already holds (scanned on refreshes that
 *   already happen — no polling loop of its own);
 * - mapping verdicts the Replacement Mapping resolver just produced
 *   (T2a/T2b) — the watch reads verdicts, never fires searches of its own.
 *
 * ADR-0037: the refusal is absolute — a refused source is never an
 * appearance, a refused member never satisfies a watch, so a refused-only
 * union card never notifies. The exactly-once discipline is per source:
 * the same source appearing again is silence, a NEW source appearing
 * notifies again. The seen-state is remembered only when the caller
 * persists [Appearance.next] — an appearance whose result is dropped can
 * legitimately repeat.
 */
object SourceWatchPolicy {

    /** One mapping-verdict observation fed by the Replacement Mapping (T2a/T2b). */
    data class MappingVerdict(
        val mergeKey: String,
        val sourceId: String
    )

    /**
     * The watch bookkeeping: which Works are watched (mergeKey → workId)
     * and which sources have already been notified per Work (mergeKey →
     * sourceIds).
     */
    data class SeenState(
        val watched: Map<String, String> = emptyMap(),
        val seen: Map<String, Set<String>> = emptyMap()
    )

    data class Appearance(
        val workIds: List<String>,
        /** The Work of the single appearance, when exactly one fired. */
        val workId: String?,
        /** mergeKey → newly appearing (non-refused, not-yet-notified) sourceIds. */
        val appearedByMergeKey: Map<String, Set<String>>,
        /** The set of all newly appearing sourceIds across Works. */
        val appearedSourceIds: Set<String>,
        /** Mapping verdicts that count as appearances and must be persisted. */
        val verdicts: List<MappingVerdict>,
        /** The updated state to persist alongside the notification. */
        val next: SeenState
    )

    /**
     * Evaluates one zero-request scan. Returns null when nothing new
     * appeared; the union card scan and the mapping verdicts are treated
     * identically.
     */
    fun evaluate(
        state: SeenState,
        catalog: List<GlobalSearchResult>,
        mappingVerdicts: List<MappingVerdict> = emptyList(),
        refusedSources: Set<String> = emptySet()
    ): Appearance? {
        if (state.watched.isEmpty()) return null
        val refused = refusedSources.filter { it.isNotBlank() }
        val newSeen = LinkedHashMap<String, MutableSet<String>>(state.seen.size)
        state.seen.forEach { (k, v) -> newSeen[k] = LinkedHashSet(v) }
        val appeared = linkedMapOf<String, MutableSet<String>>()
        val workIds = linkedSetOf<String>()
        val firedVerdicts = mutableListOf<MappingVerdict>()

        fun observe(mergeKey: String, sourceId: String, verdict: MappingVerdict?) {
            val workId = state.watched[mergeKey] ?: return
            if (sourceId.isBlank() || sourceId in refused) return
            val seen = newSeen.getOrPut(mergeKey) { linkedSetOf() }
            if (sourceId in seen) return
            seen += sourceId
            appeared.getOrPut(mergeKey) { linkedSetOf() } += sourceId
            workIds += workId
            verdict?.let(firedVerdicts::add)
        }

        for (result in catalog) {
            for (member in result.sources) {
                observe(result.mergeKey, member.sourceId, verdict = null)
            }
        }
        for (verdict in mappingVerdicts) {
            observe(verdict.mergeKey, verdict.sourceId, verdict = verdict)
        }

        if (appeared.isEmpty()) return null
        return Appearance(
            workIds = workIds.toList(),
            workId = workIds.singleOrNull(),
            appearedByMergeKey = appeared,
            appearedSourceIds = appeared.values.flatten().toSet(),
            verdicts = firedVerdicts,
            next = state.copy(seen = newSeen)
        )
    }
}
