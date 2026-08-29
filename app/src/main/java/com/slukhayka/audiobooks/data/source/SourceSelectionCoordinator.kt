package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.db.SourceEntity
import kotlin.math.max

/**
 * #429 — Runtime Source selection coordinator.
 *
 * One coordinator serves playback, search, recommendations, and feed: it takes
 * an Edition's candidate sources, an operation kind, a monotonic budget, and a
 * probe function, then returns the best available source — or a
 * browser-required/unavailable verdict.
 *
 * ### Priority order
 * 1. **LOCAL** — an already-downloaded file on disk (no probe needed)
 * 2. **DIRECT** — a server-fetch source whose stream URL resolves directly
 * 3. **UNKNOWN** — a source whose access kind is not yet determined
 * 4. **BROWSER** — a session-bound source that requires the in-app browser
 *
 * Within one category, tie-break: source name (ascending), source id
 * (ascending), then URL (ascending).
 *
 * ### Budget
 * All DIRECT and UNKNOWN probes of one `select()` call share [budgetMs] of
 * wall-clock time. Probing stops after the first success. LOCAL candidates
 * are never probed. BROWSER candidates are never probed — they return as
 * [SelectionResult.BrowserRequired] only when no earlier category succeeds.
 *
 * ### Edition isolation
 * The coordinator never crosses Edition boundaries: every candidate must
 * belong to the same Edition. Callers that pass mixed-Edition candidates
 * get undefined behaviour — the coordinator is scoped per-Edition by design.
 *
 * ### Successful source becomes current only after verified opening
 * The coordinator returns the *selected* candidate but does not mutate any
 * Listening State or Source row. The caller is responsible for persisting the
 * selection only after the source has been verified open (e.g. after a
 * successful player prepare).
 */
object SourceSelectionCoordinator {

    /**
     * Priority rank of a source category. Lower rank = higher priority.
     * The ordinal alone defines the order; the values are stable constants.
     */
    enum class SourceCategory(val rank: Int) {
        LOCAL(0),
        DIRECT(1),
        UNKNOWN(2),
        BROWSER(3);

        companion object {
            /**
             * Derive the category from a [SourceEntity] and the adapter's
             * [SourceAdapter.sessionBound] flag.
             *
             * Rules:
             * - type `"local"` → LOCAL
             * - sessionBound adapter → BROWSER
             * - everything else → DIRECT (the adapter can fetch the page
             *   through the plain transport)
             */
            fun of(source: SourceEntity, sessionBound: Boolean): SourceCategory = when {
                source.type == "local" -> LOCAL
                sessionBound -> BROWSER
                else -> DIRECT
            }
        }
    }

    /**
     * What the source is needed for. The operation kind influences budget
     * allocation but not the priority order.
     */
    enum class OperationKind {
        /** Opening a book for listening. */
        PLAYBACK,
        /** Global or source-scoped search. */
        SEARCH,
        /** Recommendation resolution. */
        RECOMMENDATIONS,
        /** Feed / catalogue enumeration. */
        FEED
    }

    /**
     * A source candidate for one Edition, carrying its category and the
     * entity the caller already knows about.
     */
    data class SourceCandidate(
        val source: SourceEntity,
        val category: SourceCategory
    )

    /** Outcome of a probe against one source's stream URL or page. */
    sealed class ProbeResult {
        /** The source is reachable (HTTP 200–299 on a HEAD/GET probe). */
        data object Success : ProbeResult()
        /** The source returned an error or was unreachable. */
        data object Failure : ProbeResult()
        /** The probe exceeded the remaining budget. */
        data object Timeout : ProbeResult()
    }

    /** The coordinator's verdict. */
    sealed class SelectionResult {
        /**
         * A source was selected and verified reachable.
         *
         * @property candidate the winning candidate
         * @property elapsedMs wall-clock time spent probing (0 for LOCAL)
         */
        data class Selected(
            val candidate: SourceCandidate,
            val elapsedMs: Long = 0L
        ) : SelectionResult()

        /**
         * No earlier category succeeded; a browser session is required.
         * The caller must open the in-app browser for the listener.
         *
         * @property candidate the BROWSER candidate (first by tie-break)
         */
        data class BrowserRequired(
            val candidate: SourceCandidate
        ) : SelectionResult()

        /**
         * No candidate could be reached and no browser candidate exists.
         */
        data object Unavailable : SelectionResult()
    }

    /**
     * A monotonic clock for budget tracking. Production returns
     * `System.nanoTime()`; tests inject a fake.
     */
    fun interface Clock {
        /** Returns a monotonic timestamp in milliseconds. */
        fun nowMs(): Long
    }

    /** The default wall-clock: `System.nanoTime()` converted to ms. */
    val DefaultClock: Clock = Clock { System.nanoTime() / 1_000_000 }

    /**
     * Probe one source: returns [ProbeResult] within the remaining budget.
     * The implementation should be a lightweight HEAD or GET on the source's
     * first stream URL or page URL. Production wires a real HTTP probe;
     * tests inject a fake.
     *
     * @param source the source entity to probe
     * @param remainingMs the time left in the shared budget
     * @return the probe verdict, or [ProbeResult.Timeout] if the remaining
     *         budget is already exhausted before the call starts
     */
    fun interface SourceProbe {
        suspend fun probe(source: SourceEntity, remainingMs: Long): ProbeResult
    }

    /** The default budget for all DIRECT / UNKNOWN probes in one call. */
    const val DEFAULT_BUDGET_MS: Long = 10_000L

    /**
     * Select the best source for an Edition.
     *
     * @param operation the operation requesting a source
     * @param candidates the Edition's source candidates (must all share the
     *        same editionId; the coordinator does not validate this)
     * @param probe the probe function for testing reachability
     * @param clock the monotonic clock for budget tracking
     * @param budgetMs the shared budget for DIRECT/UNKNOWN probes (default 10s)
     * @return the selection verdict
     */
    suspend fun select(
        operation: OperationKind,
        candidates: List<SourceCandidate>,
        probe: SourceProbe,
        clock: Clock = DefaultClock,
        budgetMs: Long = DEFAULT_BUDGET_MS
    ): SelectionResult {
        if (candidates.isEmpty()) return SelectionResult.Unavailable

        // Phase 1: LOCAL — no probe needed, instant win.
        val locals = candidates.filter { it.category == SourceCategory.LOCAL }
        if (locals.isNotEmpty()) {
            return SelectionResult.Selected(
                candidate = locals.minBy(::tieBreakKey),
                elapsedMs = 0L
            )
        }

        // Phase 2 & 3: DIRECT + UNKNOWN — share the probe budget.
        val probeables = candidates
            .filter { it.category == SourceCategory.DIRECT || it.category == SourceCategory.UNKNOWN }
            .sortedWith(compareBy<SourceCandidate> { it.category.rank }.thenBy(::tieBreakKey))

        // Phase 4: BROWSER — fallback only, no probe.
        val browsers = candidates
            .filter { it.category == SourceCategory.BROWSER }
            .sortedBy(::tieBreakKey)

        val startMs = clock.nowMs()
        var elapsedMs = 0L

        for (candidate in probeables) {
            val remainingMs = max(0L, budgetMs - elapsedMs)
            if (remainingMs <= 0L) break

            val result = probe.probe(candidate.source, remainingMs)
            elapsedMs = clock.nowMs() - startMs

            when (result) {
                is ProbeResult.Success -> {
                    return SelectionResult.Selected(
                        candidate = candidate,
                        elapsedMs = elapsedMs
                    )
                }
                is ProbeResult.Failure -> continue
                is ProbeResult.Timeout -> break
            }
        }

        // Phase 4: BROWSER fallback.
        if (browsers.isNotEmpty()) {
            return SelectionResult.BrowserRequired(
                candidate = browsers.first()
            )
        }

        return SelectionResult.Unavailable
    }

    /**
     * Deterministic tie-break key: source name → source id → url.
     * Lower lexicographic value wins.
     */
    private fun tieBreakKey(candidate: SourceCandidate): String =
        "${sourceDisplayName(candidate.source.type)}|${candidate.source.id}|${candidate.source.url}"
}
