package com.slukhayka.audiobooks.data.availability

/**
 * ADR-0042 §5 (spec-56, ticket #730) — the priority queue of availability
 * checks. The fixed prewarm limit (five books) is gone: a check is requested
 * for a reason, and the reason sets the order.
 *
 * - [Priority.VIEWPORT] — a card the listener can actually see right now;
 *   served first, whatever else is pending.
 * - [Priority.BACKLOG] — the daily delta scan of the rest of the library;
 *   served only after the visible cards.
 *
 * The queue is pure JVM and deterministic: a mergeKey is queued at most once
 * (a visible request promotes a pending backlog entry), insertion order
 * breaks ties, and [next] pops the highest-priority, oldest entry.
 */
class AvailabilityCheckQueue {

    enum class Priority { VIEWPORT, BACKLOG }

    private val pending = LinkedHashMap<String, Priority>()

    /** Queues every key as visible, promoting a pending backlog entry. */
    fun requestVisible(mergeKeys: Collection<String>) = request(mergeKeys, Priority.VIEWPORT)

    /** Queues every key as backlog; a visible request still outranks it. */
    fun requestBacklog(mergeKeys: Collection<String>) = request(mergeKeys, Priority.BACKLOG)

    private fun request(mergeKeys: Collection<String>, priority: Priority) {
        for (key in mergeKeys) {
            if (key.isBlank()) continue
            val current = pending[key]
            if (current == null || priority.ordinal < current.ordinal) {
                // Re-insert so a promoted key also refreshes its tie-break
                // position; a plain backlog re-request keeps its place.
                pending.remove(key)
                pending[key] = priority
            }
        }
    }

    /** The next key to check, or null when nothing is pending. */
    fun next(): String? {
        if (pending.isEmpty()) return null
        var bestKey: String? = null
        var bestPriority = Priority.BACKLOG
        for ((key, priority) in pending) {
            if (bestKey == null || priority.ordinal < bestPriority.ordinal) {
                bestKey = key
                bestPriority = priority
                if (priority == Priority.VIEWPORT) break
            }
        }
        return bestKey?.also { pending.remove(it) }
    }

    val size: Int get() = pending.size

    fun isEmpty(): Boolean = pending.isEmpty()

    fun clear() = pending.clear()
}

/**
 * Spec-56 T3 (#730) — the once-a-day throttle of the backlog delta scan. The
 * scan is due on a fresh install (no mark) or after a full interval; a
 * restart inside the interval is never due, so it never repeats the scan.
 */
object AvailabilityDailyScan {

    const val INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    fun isDue(lastScanAtMs: Long, nowMs: Long): Boolean =
        lastScanAtMs <= 0L || nowMs - lastScanAtMs >= INTERVAL_MS
}

