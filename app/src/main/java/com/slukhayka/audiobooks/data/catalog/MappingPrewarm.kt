package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * ADR-0037 §6 follow-up (2026-09-10) — the background mapping prewarm:
 * library Works with NO direct source (4read-only, refused-only, sourceless)
 * get their replacement verdict resolved in the background, so the listener's
 * tap finds a warm in-process memo — and, after a successful volley, a warm
 * shared [com.slukhayka.audiobooks.data.search.SearchCache] entry — instead
 * of paying for a live search on the tap path.
 *
 * Discipline:
 * - **Resolve only, never import.** The library changes only on a listener
 *   touch; the prewarm merely answers "is there a direct counterpart?".
 * - **Sequential and bounded.** At most [limit] works per run, one at a
 *   time with [pauseMillis] between them; the resolver itself owns the
 *   request discipline (union-first, at most one volley, 6h/15m memo), and
 *   the Source Request Gate owns the politeness of every request.
 * - **Best-effort and silent.** A failing readout, a failing resolve or a
 *   failing pause degrades to "nothing warmed"; cancellation is rethrown.
 * - **Only works without a direct source.** A Work that already has a row
 *   on a DIRECT source is skipped — there is nothing to map.
 *
 * Pure JVM (seams as lambdas, fakes over mocks) so the selection and the
 * touch-budget are unit-testable without Android.
 */
class MappingPrewarm(
    private val books: suspend () -> List<AudiobookEntity>,
    private val resolve: suspend (title: String, author: String, mergeKey: String) -> SourceReplacementMapping.Match?,
    private val limit: Int = DEFAULT_LIMIT,
    private val pause: Long = DEFAULT_PAUSE_MILLIS,
    private val pauseMillis: suspend (Long) -> Unit = { delay(it) },
    /**
     * ADR-0042 §1 — reports each honest verdict (a miss is null) so the
     * caller can persist it for the library card. A resolve that THROWS is
     * not a verdict and is not reported: offline never fabricates a fresh
     * "not found". Best-effort; a failing recorder never stops the run.
     */
    private val onVerdict: suspend (mergeKey: String, match: SourceReplacementMapping.Match?) -> Unit =
        { _, _ -> }
) {

    /**
     * Resolves up to [limit] candidate Works; returns how many produced a
     * positive verdict. Never throws except on cancellation.
     */
    suspend fun runOnce(): Int {
        val all = try {
            books()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return 0
        }
        val byKey = all.groupBy { keyOf(it) }.filterKeys { it.isNotBlank() }
        val candidates = all
            .filter { row -> keyOf(row).isNotBlank() && !hasDirectSource(byKey[keyOf(row)].orEmpty()) }
            .distinctBy { keyOf(it) }
            .take(limit)

        var warmed = 0
        candidates.forEachIndexed { index, row ->
            if (index > 0) {
                try {
                    pauseMillis(pause)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failing pause (fixture) must not lose the run.
                }
            }
            try {
                val match = resolve(row.title, row.author, keyOf(row))
                if (match != null) warmed++
                reportVerdict(keyOf(row), match)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best-effort: one failing Work never stops the run.
            }
        }
        return warmed
    }

    private suspend fun reportVerdict(mergeKey: String, match: SourceReplacementMapping.Match?) {
        try {
            onVerdict(mergeKey, match)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best-effort: recording a verdict never stops the run.
        }
    }

    private fun keyOf(row: AudiobookEntity): String =
        row.mergeKey.takeIf { !it.isNullOrBlank() } ?: MergeKey.keyFor(row.title, row.author)

    private fun hasDirectSource(rows: List<AudiobookEntity>): Boolean = rows.any { row ->
        row.sourceUrl.isNotBlank() &&
            SourceAccessPolicy.modeFor(sourceIdForUrl(row.sourceUrl)) == SourceAccessMode.DIRECT
    }

    companion object {
        /** Small on purpose: the first launches warm the listener's pain points. */
        const val DEFAULT_LIMIT = 5

        /** Human rhythm between works; the gate throttles the requests themselves. */
        const val DEFAULT_PAUSE_MILLIS = 3_000L
    }
}
