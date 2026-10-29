package com.slukhayka.audiobooks.data.metadata

import java.util.concurrent.ConcurrentHashMap

/**
 * #1589 — the bounded memo the pure normalization rules sit behind.
 *
 * The achievements pass re-normalizes the SAME strings on every Room
 * invalidation: one emission walks the whole own library against the curated
 * universes and collections, so the same title/author reaches the normalizers
 * thousands of times per pass. The #1570 stack showed a fresh
 * `Pattern.compile` per title; #1572 moved the pass off the main thread, it
 * did not make it cheaper. A memo turns a repeat into a map lookup, so the
 * regex pipeline and its allocations run once per DISTINCT string instead of
 * once per comparison.
 *
 * This is safe by construction because the memoized functions are PURE (same
 * input → same output, no state, no I/O): a hit returns exactly what a
 * recomputation would. The map is bounded and clears wholesale when full — the
 * only cost of an eviction is recomputation, never a different answer. A
 * `ConcurrentHashMap` keeps it safe on the `Dispatchers.Default` pass (#1572);
 * a race can only compute the same value twice.
 *
 * The bound is honest about the cost: normalizer keys are titles/authors from
 * the catalogue, not an unbounded input stream, so a few thousand entries
 * cover the working set. Clearing resets the working set rather than growing
 * without limit.
 */
internal class NormalizationMemo(
    private val maxEntries: Int = MAX_ENTRIES
) {
    private val values = ConcurrentHashMap<String, String>()

    /** Returns the memoized result for [key], computing it once via [compute]. */
    fun normalized(key: String, compute: (String) -> String): String {
        values[key]?.let { return it }
        val value = compute(key)
        if (values.size >= maxEntries) values.clear()
        values[key] = value
        return value
    }

    private companion object {
        const val MAX_ENTRIES = 4096
    }
}
