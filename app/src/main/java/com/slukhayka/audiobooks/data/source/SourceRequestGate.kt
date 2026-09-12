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
 * the fresh cache, single-flight coalescing and the per-host throat are
 * testable with a fake clock before any transport touches them.
 *
 * Order of every request: fresh cache (zero requests, zero tokens) -> token
 * from the per-domain bucket -> single-flight -> ONE in-flight request Per
 * Source host with a minimum jitter gap between that host's own consecutive
 * requests. The throat is per host, never global: different Sources fetch
 * concurrently, so a tap or a global search can never queue behind unrelated
 * hosts (the 2026-09-10 responsiveness regression: a global throat + blocking
 * waits serialized the whole app).
 *
 * Listener actions take the head of their host's queue; background spends
 * budget only when the bucket is more than half full. Audio streams never
 * pass through here (ADR-0039 §9).
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
 * bucket (4 of 6), and consecutive same-host requests separated by 200–800 ms.
 * The listener wait cap is deliberately short: a blocking transport call may
 * not freeze the UI for seconds.
 */
data class SourceGateParams(
    val bucketCapacity: Int = 6,
    val refillIntervalMs: Long = 10_000,
    val backgroundMinTokens: Int = 4,
    val listenerWaitCapMs: Long = 1_500,
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
        val canWait: Boolean,
        val granted: CompletableDeferred<Unit>,
        val seq: Long
    )

    /** One source host's queue: one request in flight, priority waiters, jitter gap. */
    private class HostMouth {
        val mutex = Mutex()
        val waiters = mutableListOf<Waiter>()
        var busy = false
        var lastFinishedAtMs: Long? = null
        var seq = 0L
    }

    private sealed interface Admission {
        data class Granted(val state: SourceBucketState) : Admission
        data class Deferred(val retryAfterMs: Long) : Admission
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<GateOutcome<Any?>>>()
    private val mouths = ConcurrentHashMap<String, HostMouth>()

    /**
     * One gated request. [cacheTtlMillis] > 0 opts the call into the fresh
     * cache (positive results for its TTL, failures for [SourceGateParams.negativeTtlMs]);
     * 0 means "current truth, no cache". [canWait] is FALSE on the legacy
     * blocking transport door: a dry bucket then defers immediately and the
     * jitter gap is skipped, so a UI-thread caller is never parked.
     */
    suspend fun <T : Any> run(
        url: String,
        requestClass: SourceRequestClass,
        cacheTtlMillis: Long = 0L,
        canWait: Boolean = true,
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
            val outcome = withThroat(hostOf(url), requestClass, canWait) {
                fetchUnderBudget(url, requestClass, cacheTtlMillis, canWait, fetch)
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
        canWait: Boolean,
        fetch: suspend () -> T?
    ): GateOutcome<T> {
        val host = hostOf(url)
        val admission = admit(host, requestClass, canWait)
        when (admission) {
            is Admission.Deferred -> return GateOutcome.Deferred(admission.retryAfterMs)
            is Admission.Granted -> budgetStore.save(host, admission.state)
        }

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
    private suspend fun admit(host: String, requestClass: SourceRequestClass, canWait: Boolean): Admission {
        val now = clock()
        val state = refill(budgetStore.load(host) ?: SourceBucketState(params.bucketCapacity, now), now)
        val minimum =
            if (requestClass == SourceRequestClass.BACKGROUND) params.backgroundMinTokens else 1
        if (state.tokens >= minimum) {
            return Admission.Granted(state.copy(tokens = state.tokens - 1))
        }

        val retryAfter = retryAfterMs(state, minimum, now)
        if (canWait && requestClass == SourceRequestClass.LISTENER_ACTION &&
            retryAfter <= params.listenerWaitCapMs
        ) {
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

    // --- the per-host throat with class priority and the jitter gap ---

    private suspend fun <T> withThroat(
        host: String,
        requestClass: SourceRequestClass,
        canWait: Boolean,
        block: suspend () -> T
    ): T {
        val mouth = mouths.computeIfAbsent(host) { HostMouth() }
        acquireThroat(mouth, requestClass, canWait)
        try {
            return block()
        } finally {
            releaseThroat(mouth)
        }
    }

    private suspend fun acquireThroat(mouth: HostMouth, requestClass: SourceRequestClass, canWait: Boolean) {
        val granted = CompletableDeferred<Unit>()
        var direct = false
        mouth.mutex.withLock {
            if (!mouth.busy) {
                mouth.busy = true
                direct = true
            } else {
                mouth.waiters.add(Waiter(requestClass, canWait, granted, ++mouth.seq))
            }
        }
        if (direct) {
            if (canWait) pauseJitterGap(mouth)
        } else {
            granted.await()
        }
    }

    private suspend fun releaseThroat(mouth: HostMouth) {
        val next = mouth.mutex.withLock {
            if (mouth.waiters.isEmpty()) {
                mouth.busy = false
                null
            } else {
                val index = mouth.waiters.indices.minWithOrNull(
                    compareBy({ priorityOf(mouth.waiters[it].requestClass) }, { mouth.waiters[it].seq })
                ) ?: 0
                mouth.waiters.removeAt(index)
            }
        }
        if (next == null) {
            mouth.lastFinishedAtMs = clock()
            return
        }
        if (next.canWait) {
            pauseJitterGap(mouth)
        }
        next.granted.complete(Unit)
    }

    private suspend fun pauseJitterGap(mouth: HostMouth) {
        val last = mouth.lastFinishedAtMs ?: return
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
