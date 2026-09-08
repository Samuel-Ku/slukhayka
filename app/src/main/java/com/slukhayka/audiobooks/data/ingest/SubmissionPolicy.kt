package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore

/**
 * ADR-0035 / #607 — the anti-spam policy of listener submissions, second
 * line behind the playback verdict (which stays the main barrier). Pure JVM,
 * fixture-tested (the PacingPolicy / StreamHealPolicy precedent):
 *
 *  - **verdict** — a submission is publishable only after a REAL playback
 *    verdict ([SubmissionVerification]); a bare link insert never is;
 *  - **daily limit** — at most [DAILY_SUBMISSION_LIMIT] submissions per
 *    device per UTC day. The counters live in the SHARED base
 *    ([SharedBookMetaStore.getSubmissionCount]) so a reinstall cannot reset
 *    the budget; the remaining budget is exposed honestly ([remainingToday]);
 *  - **URL dedup** — the same normalized URL is published once; a repeat is
 *    honestly refused ([Reason.ALREADY_PUBLISHED]).
 *
 * The policy only DECIDES and CONSUMES; the payload assembly and the store
 * write stay in [SubmissionPublisher].
 */
class SubmissionPolicy(
    private val sharedStore: SharedBookMetaStore,
    private val verification: SubmissionVerification,
    private val clock: () -> Long = System::currentTimeMillis
) {

    enum class Reason {
        /** No real playback verdict yet — the main barrier. */
        NOT_VERIFIED,

        /** The per-device daily budget is exhausted. */
        DAILY_LIMIT_REACHED,

        /** The normalized URL is already published. */
        ALREADY_PUBLISHED
    }

    data class Decision(
        val allowed: Boolean,
        val reason: Reason?,
        /** The honest remaining budget for this device today (0 when refused by the limit). */
        val remainingToday: Int
    )

    /** Decides whether [url] may be published from [deviceId] under [sourceId]. */
    suspend fun decide(sourceId: String, url: String, deviceId: String): Decision {
        if (!verification.isVerified(sourceId)) {
            return Decision(allowed = false, reason = Reason.NOT_VERIFIED, remainingToday = 0)
        }
        val count = sharedStore.getSubmissionCount(deviceId, dayKeyOf(clock()))
        val remaining = (DAILY_SUBMISSION_LIMIT - count).coerceAtLeast(0).toInt()
        if (count >= DAILY_SUBMISSION_LIMIT) {
            return Decision(allowed = false, reason = Reason.DAILY_LIMIT_REACHED, remainingToday = remaining)
        }
        if (sharedStore.getSubmission(url.trim()) != null) {
            return Decision(allowed = false, reason = Reason.ALREADY_PUBLISHED, remainingToday = remaining)
        }
        return Decision(allowed = true, reason = null, remainingToday = remaining)
    }

    /** Consumes one slot of the per-device daily budget (after a successful publish). */
    suspend fun consume(deviceId: String) {
        sharedStore.incrementSubmissionCount(deviceId, dayKeyOf(clock()))
    }

    /** The moment the real playback verdict fired for [sourceId], or null. */
    fun verifiedAt(sourceId: String): Long? = verification.verifiedAt(sourceId)

    /** The honest remaining budget for the device today — the UI read. */
    suspend fun remainingToday(deviceId: String): Int {
        val count = sharedStore.getSubmissionCount(deviceId, dayKeyOf(clock()))
        return (DAILY_SUBMISSION_LIMIT - count).coerceAtLeast(0).toInt()
    }

    companion object {
        /** The per-device per-day submission budget (spec-601: «денний ліміт на пристрій»). */
        const val DAILY_SUBMISSION_LIMIT = 10L

        /** One UTC day — deterministic, timezone-free, testable. */
        private const val DAY_MILLIS = 86_400_000L

        /** The UTC epoch-day key of [nowMillis]. */
        fun dayKeyOf(nowMillis: Long): String = (nowMillis / DAY_MILLIS).toString()
    }
}