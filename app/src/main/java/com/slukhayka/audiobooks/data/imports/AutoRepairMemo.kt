package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.catalog.CatalogAvailabilityPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * #470 (spec #462) — the bounded attempt memo for AUTOMATIC repairs
 * (ADR-0019's «авто-цикли» doctrine: self-healing must never loop).
 *
 * Any repair the app runs without asking the listener first consults this
 * memo keyed by the Work-scoped identity (the book id): a SUCCEEDED repair is
 * not repeated while its positive verdict is fresh (6 hours), and a FAILED
 * one blocks further automatic attempts for the negative window (15 minutes).
 * Both TTLs and the exact-boundary staleness are the Edition Availability
 * Assertion discipline ([CatalogAvailabilityPolicy.isFresh]) — the same
 * pattern as [com.slukhayka.audiobooks.data.catalog.SluhayuaCrossResolve]'s
 * per-Work verdict memo.
 *
 * The memo bounds only the AUTOMATIC path: the listener can still run the
 * same repair interactively (the confirmation dialog) at any time — an
 * explicit human action is never a loop.
 */
class AutoRepairMemo(private val clock: () -> Long = System::currentTimeMillis) {

    private data class Verdict(val succeeded: Boolean, val observedAtMillis: Long)

    private val verdicts = ConcurrentHashMap<String, Verdict>()

    /** True when an automatic repair for [key] may run now. */
    fun canAttempt(key: String): Boolean {
        val verdict = verdicts[key] ?: return true
        return !CatalogAvailabilityPolicy.isFresh(verdict.succeeded, verdict.observedAtMillis, clock())
    }

    /** Records an automatic repair success — the key is skipped while fresh. */
    fun recordSuccess(key: String) {
        verdicts[key] = Verdict(succeeded = true, observedAtMillis = clock())
    }

    /** Records an automatic repair failure — bounded negative window. */
    fun recordFailure(key: String) {
        verdicts[key] = Verdict(succeeded = false, observedAtMillis = clock())
    }

    companion object {
        /**
         * The process-wide memo behind the recovery coordinator's default.
         * [BrowserRecoveryCoordinator] instances are created per recovery
         * call, so the bound must outlive any single instance.
         */
        val shared = AutoRepairMemo()
    }
}
