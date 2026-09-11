package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0039 / spec #681 T1 (#682) — the ONE politeness gate for every
 * HTML/API request to a Source domain. T2 wires it into `HttpFetcher`; this
 * file is the pure-JVM decision module so the classes, the persistent budget,
 * the fresh cache, single-flight coalescing and the one-in-flight throat are
 * testable with a fake clock before any transport touches them.
 *
 * Order of every request: fresh cache (zero requests, zero tokens) -> token
 * from the per-domain bucket -> single-flight -> global throat with a minimum
 * jitter gap between any two source requests. Listener actions take the head
 * of the queue; background spends budget only when the bucket is more than
 * half full. Audio streams never pass through here (ADR-0039 §9).
 */
enum class SourceRequestClass {
    /** A tap, import, play or re-resolve: never starves behind background work. */
    LISTENER_ACTION,

    /** Feed/catalog refresh after the TTL: one token per pass. */
    TTL_REFRESH,

    /** Workers, delta syncs, duration probes: only when the budget is plentiful. */
    BACKGROUND,

    /** Cover fetches: cache-first and the lowest queue class. */
    COVER
}

/**
 * The initial numbers ADR-0039 §Числа calls settings to verify, not measured
 * results: capacity 6, one token per 10 s, background strictly above half the
 * bucket (4 of 6), a listener action waits at most 5 s before the honest
 * deferred state, and consecutive requests are separated by 200–800 ms.
 */
data class SourceGateParams(
    val bucketCapacity: Int = 6,
    val refillIntervalMs: Long = 10_000,
    val backgroundMinTokens: Int = 4,
    val listenerWaitCapMs: Long = 5_000,
    val jitterMinMs: Long = 200,
    val jitterMaxMs: Long = 800,
    /**
     * A failed fetch (null) is remembered this long when the call opted into
     * caching; an untouched value keeps probing every time.
     */
    val negativeTtlMs: Long = 15 * 60 * 1000
)

/** A domain's persisted budget: whole tokens plus the last refill anchor. */
data class SourceBucketState(
    val tokens: Int,
    val lastRefillAtMs: Long
)

/** The gate's outcome; `Deferred` is the honest "budget is resting" state. */
sealed interface GateOutcome<out T> {
    /** Served from the fresh cache: zero network requests, zero tokens. */
    data class Fresh<T>(val value: T) : GateOutcome<T>

    /** Fetched from the network (or joined an in-flight fetch for the URL). */
    data class Fetched<T>(val value: T) : GateOutcome<T>

    /** The budget could not be spent: wait [retryAfterMs] or show the honest state. */
    data class Deferred(val retryAfterMs: Long) : GateOutcome<Nothing>

    /** The fetch failed; never a fabricated value. */
    data object Unavailable : GateOutcome<Nothing>
}

/** The persistent per-domain budget port (spec: survives a process restart). */
interface SourceGateBudgetStore {
    fun load(host: String): SourceBucketState?
    fun save(host: String, state: SourceBucketState)
}

/** The test/default carrier; `SharedPreferencesSourceGateBudgetStore` is the production one. */
class InMemorySourceGateBudgetStore : SourceGateBudgetStore {
    private val states = ConcurrentHashMap<String, SourceBucketState>()
    override fun load(host: String): SourceBucketState? = states[host]
    override fun save(host: String, state: SourceBucketState) {
        states[host] = state
    }
}

class SourceRequestGate(
    private val params: SourceGateParams = SourceGateParams(),
    private val budgetStore: SourceGateBudgetStore = InMemorySourceGateBudgetStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default
) {

    private data class CacheEntry(val value: Any?, val storedAtMs: Long, val ttlMs: Long)

    private class Waiter(
        val requestClass: SourceRequestClass,
        val granted: CompletableDeferred<Unit>,
        val seq: Long
    )

    private sealed interface Admission {
        data class Granted(val state: SourceBucketState) : Admission
        data class Deferred(val retryAfterMs: Long) : Admission
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<GateOutcome<Any?>>>()

    private val mouthMutex = Mutex()
    private val waiters = mutableListOf<Waiter>()
    private var busy = false
    private var lastFinishedAtMs: Long? = null
    private var waiterSeq = 0L

    /**
     * One gated request. [cacheTtlMillis] > 0 opts the call into the fresh
     * cache (positive results for its TTL, failures for [SourceGateParams.negativeTtlMs]);
     * 0 means "current truth, no cache". [fetch] returns null on any failure.
     */
    suspend fun <T : Any> run(
        url: String,
        requestClass: SourceRequestClass,
        cacheTtlMillis: Long = 0L,
        fetch: suspend () -> T?
    ): GateOutcome<T> {
        cached(url, allowFailure = cacheTtlMillis > 0L)?.let { entry ->
            return entry.toOutcome()
        }

        val leader = CompletableDeferred<GateOutcome<Any?>>()
        val existing = inFlight.putIfAbsent(url, leader)
        if (existing != null) {
            return existing.await().cast()
        }

        try {
            val outcome = withThroat(requestClass) {
                fetchUnderBudget(url, requestClass, cacheTtlMillis, fetch)
            }
            leader.complete(outcome)
            return outcome
        } catch (error: Throwable) {
            leader.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(url, leader)
        }
    }

    private suspend fun <T : Any> fetchUnderBudget(
        url: String,
        requestClass: SourceRequestClass,
        cacheTtlMillis: Long,
        fetch: suspend () -> T?
    ): GateOutcome<T> {
        val host = hostOf(url)
        val admission = admit(host, requestClass)
        when (admission) {
            is Admission.Deferred -> return GateOutcome.Deferred(admission.retryAfterMs)
            is Admission.Granted -> budgetStore.save(host, admission.state)
        }

        // G1 (spec `2026-09-10-remove-4read-source`) — the jitter spaces out
        // real fetches only: a request the budget defers returns above,
        // before this gap, so it takes no throat slot and never sleeps — a
        // caller walking many URLs on an exhausted budget cannot stall the
        // shared gate.
        pauseJitterGap()
        val value = fetch()
        if (value == null) {
            if (cacheTtlMillis > 0L && params.negativeTtlMs > 0L) {
                cache[url] = CacheEntry(null, clock(), params.negativeTtlMs)
            }
            return GateOutcome.Unavailable
        }
        if (cacheTtlMillis > 0L) {
            cache[url] = CacheEntry(value, clock(), cacheTtlMillis)
        }
        return GateOutcome.Fetched(value)
    }

    /** The class rule: background needs strictly more than half the bucket. */
    private suspend fun admit(host: String, requestClass: SourceRequestClass): Admission {
        val now = clock()
        val state = refill(budgetStore.load(host) ?: SourceBucketState(params.bucketCapacity, now), now)
        val minimum =
            if (requestClass == SourceRequestClass.BACKGROUND) params.backgroundMinTokens else 1
        if (state.tokens >= minimum) {
            return Admission.Granted(state.copy(tokens = state.tokens - 1))
        }

        val retryAfter = retryAfterMs(state, minimum, now)
        if (requestClass == SourceRequestClass.LISTENER_ACTION && retryAfter <= params.listenerWaitCapMs) {
            sleeper(retryAfter)
            val waited = refill(state, now + retryAfter)
            if (waited.tokens >= 1) {
                return Admission.Granted(waited.copy(tokens = waited.tokens - 1))
            }
        }
        return Admission.Deferred(retryAfter)
    }

    private fun refill(state: SourceBucketState, now: Long): SourceBucketState {
        val elapsed = now - state.lastRefillAtMs
        if (elapsed < params.refillIntervalMs) return state
        val refilled = (elapsed / params.refillIntervalMs).toInt()
        val tokens = (state.tokens + refilled).coerceAtMost(params.bucketCapacity)
        return SourceBucketState(tokens, state.lastRefillAtMs + refilled.toLong() * params.refillIntervalMs)
    }

    private fun retryAfterMs(state: SourceBucketState, target: Int, now: Long): Long {
        if (state.tokens >= target) return 0L
        val needed = target - state.tokens
        val elapsed = (now - state.lastRefillAtMs).coerceAtLeast(0L)
        val untilNext = params.refillIntervalMs - (elapsed % params.refillIntervalMs)
        return (needed - 1).toLong() * params.refillIntervalMs + untilNext
    }

    private fun cached(url: String, allowFailure: Boolean): CacheEntry? {
        val entry = cache[url] ?: return null
        val age = clock() - entry.storedAtMs
        if (age < 0L || age >= entry.ttlMs) {
            cache.remove(url, entry)
            return null
        }
        if (entry.value == null && !allowFailure) return null
        return entry
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> CacheEntry.toOutcome(): GateOutcome<T> = when (val stored = value) {
        null -> GateOutcome.Unavailable
        else -> GateOutcome.Fresh(stored as T)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> GateOutcome<Any?>.cast(): GateOutcome<T> = this as GateOutcome<T>

    // --- the one-in-flight throat with class priority and the jitter gap ---

    private suspend fun <T> withThroat(requestClass: SourceRequestClass, block: suspend () -> T): T {
        acquireThroat(requestClass)
        try {
            return block()
        } finally {
            releaseThroat()
        }
    }

    private suspend fun acquireThroat(requestClass: SourceRequestClass) {
        val granted = CompletableDeferred<Unit>()
        var direct = false
        mouthMutex.withLock {
            if (!busy) {
                busy = true
                direct = true
            } else {
                waiters.add(Waiter(requestClass, granted, ++waiterSeq))
            }
        }
        if (!direct) {
            granted.await()
        }
    }

    private suspend fun releaseThroat() {
        val next = mouthMutex.withLock {
            if (waiters.isEmpty()) {
                busy = false
                null
            } else {
                val index = waiters.indices.minWithOrNull(
                    compareBy({ priorityOf(waiters[it].requestClass) }, { waiters[it].seq })
                ) ?: 0
                waiters.removeAt(index)
            }
        }
        if (next == null) {
            lastFinishedAtMs = clock()
            return
        }
        next.granted.complete(Unit)
    }

    private suspend fun pauseJitterGap() {
        val last = lastFinishedAtMs ?: return
        val span = params.jitterMaxMs - params.jitterMinMs
        val gap = params.jitterMinMs + if (span <= 0L) 0L else random.nextLong(span + 1)
        val elapsed = clock() - last
        if (elapsed < gap) {
            sleeper(gap - elapsed)
        }
    }

    private fun priorityOf(requestClass: SourceRequestClass): Int = when (requestClass) {
        SourceRequestClass.LISTENER_ACTION -> 0
        SourceRequestClass.TTL_REFRESH -> 1
        SourceRequestClass.COVER -> 2
        SourceRequestClass.BACKGROUND -> 3
    }

    private fun hostOf(url: String): String = try {
        URI(url).host?.lowercase() ?: url
    } catch (_: Exception) {
        url
    }
}
