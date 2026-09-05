package com.slukhayka.audiobooks.data.recommend

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.sqrt

/** Consent prompt timing without impressions, titles, history or account attributes. */
object RecommendationConsentPolicy {
    private const val COOLDOWN_MS = 90L * 86_400_000L

    data class State(
        val meaningfulInteractions: Int,
        val recommendationPlaybackStarts: Int,
        val promptCount: Int = 0,
        val lastDeclinedAt: Long? = null
    )

    fun isEligible(state: State, nowEpochMs: Long): Boolean {
        if (state.meaningfulInteractions < 3 || state.recommendationPlaybackStarts < 1) return false
        if (state.promptCount >= 2) return false
        val declined = state.lastDeclinedAt ?: return true
        return nowEpochMs - declined >= COOLDOWN_MS
    }
}

/**
 * Dormant shared-learning contract. No network implementation is wired into
 * the application graph; this pure seam makes the privacy gates testable.
 */
object PrivateRecommendationLearning {
    const val SCHEMA_VERSION = 1
    const val CONSENT_VERSION = 1
    const val PRODUCTION_UPLOAD_ENABLED = false

    data class WeeklyGradient(
        val schemaVersion: Int,
        val baseModelVersion: String,
        val gradient: List<Double>,
        val consentVersion: Int,
        val isoWeek: String,
        val weeklyId: String
    )

    data class EpochResult(
        val weights: RecommendationPersonalization.ScoreWeights?,
        val acceptedPayloads: Int,
        /** Raw gradients must be deleted after every attempted epoch, success or failure. */
        val discardRawPayloads: Boolean = true
    )

    data class Admission(
        val authenticated: Boolean,
        val appCheckValid: Boolean,
        val activeConsentReceipt: Boolean
    )

    /** Server-side contract only: no HMAC secret is present in the client graph. */
    fun serverWeeklyPseudonym(secret: ByteArray, listenerUid: String, isoWeek: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal("$listenerUid|$isoWeek".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun closeWeeklyEpoch(
        payloads: List<WeeklyGradient>,
        current: RecommendationPersonalization.ScoreWeights,
        activeOptIns: Int,
        privacyReviewApproved: Boolean,
        targetIsoWeek: String = payloads.firstOrNull()?.isoWeek.orEmpty(),
        admission: Admission = Admission(false, false, false)
    ): EpochResult {
        val valid = payloads.asSequence()
            .filter { valid(it) && it.isoWeek == targetIsoWeek }
            .distinctBy { it.weeklyId }
            .toList()
        val admitted = admission.authenticated && admission.appCheckValid && admission.activeConsentReceipt
        if (!PRODUCTION_UPLOAD_ENABLED || !privacyReviewApproved || !admitted || activeOptIns < 20 || valid.size < 10) {
            return EpochResult(weights = null, acceptedPayloads = valid.size)
        }
        val average = List(5) { index -> valid.map { it.gradient[index] }.average() }
        return EpochResult(weights = boundedUpdate(current, average), acceptedPayloads = valid.size)
    }

    /** Evaluation-only math; production remains hard-disabled above. */
    fun boundedUpdate(
        current: RecommendationPersonalization.ScoreWeights,
        gradient: List<Double>
    ): RecommendationPersonalization.ScoreWeights {
        require(gradient.size == 5)
        // #486: the collective channel tunes the PERSONAL block only — the
        // source-popularity component is a fixed structural weight, never
        // learned, so the personal simplex sums to 1 − popularity.
        val target = 1.0 - current.popularity
        val existing = listOf(current.semantic, current.author, current.genre, current.series, current.freshness)
        val lower = existing.map { (it - .02).coerceAtLeast(.02) }
        val upper = existing.map { (it + .02).coerceAtMost(.80) }
        val final = existing.indices.map { i ->
            (existing[i] + (.02 * gradient[i]).coerceIn(-.02, .02)).coerceIn(lower[i], upper[i])
        }.toMutableList()
        repeat(10) {
            val residual = target - final.sum()
            if (kotlin.math.abs(residual) < 1e-12) return@repeat
            val adjustable = final.indices.filter { i ->
                if (residual > 0) final[i] < upper[i] else final[i] > lower[i]
            }
            if (adjustable.isEmpty()) return@repeat
            val share = residual / adjustable.size
            adjustable.forEach { i -> final[i] = (final[i] + share).coerceIn(lower[i], upper[i]) }
        }
        return RecommendationPersonalization.ScoreWeights(
            semantic = final[0],
            author = final[1],
            genre = final[2],
            series = final[3],
            freshness = final[4],
            popularity = current.popularity
        )
    }

    /** Both client and admission boundary use the same unit-L2 clipping rule. */
    fun clipGradient(gradient: List<Double>): List<Double> {
        require(gradient.size == 5 && gradient.all { it.isFinite() })
        val norm = sqrt(gradient.sumOf { it * it })
        return if (norm <= 1.0 || norm == 0.0) gradient else gradient.map { it / norm }
    }

    private fun valid(payload: WeeklyGradient): Boolean =
        payload.schemaVersion == SCHEMA_VERSION &&
            payload.consentVersion == CONSENT_VERSION &&
            payload.baseModelVersion.isNotBlank() &&
            payload.isoWeek.matches(Regex("\\d{4}-W\\d{2}")) &&
            payload.weeklyId.isNotBlank() &&
            payload.gradient.size == 5 &&
            payload.gradient.all { it.isFinite() } &&
            sqrt(payload.gradient.sumOf { it * it }) <= 1.0000001
}
