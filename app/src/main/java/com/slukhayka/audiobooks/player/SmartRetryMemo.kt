package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.catalog.CatalogAvailabilityPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * #471 (spec #462) — the bounded attempt memo for the smart retry's
 * re-resolve (ADR-0019's no-loop doctrine), the [com.slukhayka.audiobooks.data.imports.AutoRepairMemo]
 * pattern applied to the player's «Повторити»: the re-resolve walks the
 * sources and the cross-source search, so the sequence must be bounded.
 *
 * The windows are the Edition Availability Assertion discipline
 * ([CatalogAvailabilityPolicy.isFresh]): a SUCCEEDED re-resolve is not
 * repeated while the positive verdict is fresh (6 hours — the tracks were
 * just refreshed); a FAILED one blocks the automatic re-resolve for the
 * negative window (15 minutes), so repeated taps can never hammer the
 * sources into a loop.
 *
 * The memo bounds only the AUTOMATIC re-resolve: the local-file check and
 * the explicit browser doors never consult it — an explicit human escape is
 * never a loop.
 */
class SmartRetryMemo(private val clock: () -> Long = System::currentTimeMillis) {

    private data class Verdict(val succeeded: Boolean, val observedAtMillis: Long)

    private val verdicts = ConcurrentHashMap<String, Verdict>()

    /** True when a re-resolve for [key] may run now. */
    fun canAttempt(key: String): Boolean {
        val verdict = verdicts[key] ?: return true
        return !CatalogAvailabilityPolicy.isFresh(verdict.succeeded, verdict.observedAtMillis, clock())
    }

    /** Records a re-resolve success — the key is skipped while fresh. */
    fun recordSuccess(key: String) {
        verdicts[key] = Verdict(succeeded = true, observedAtMillis = clock())
    }

    /** Records a re-resolve failure — bounded negative window. */
    fun recordFailure(key: String) {
        verdicts[key] = Verdict(succeeded = false, observedAtMillis = clock())
    }

    companion object {
        /**
         * The process-wide memo: [MainViewModel] instances are recreated with
         * the Activity, the bound must outlive any single instance.
         */
        val shared = SmartRetryMemo()
    }
}
