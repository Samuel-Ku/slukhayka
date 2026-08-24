package com.slukhayka.audiobooks.data.recommend

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecommendationSettings(
    val localPersonalizationEnabled: Boolean = true,
    /** Remains false until the documented privacy/legal/security launch gates pass. */
    val sharedLearningConsent: Boolean = false,
    val meaningfulInteractions: Int = 0,
    val recommendationPlaybackStarts: Int = 0,
    val consentPromptCount: Int = 0,
    val lastConsentDeclinedAt: Long? = null,
    val consentGrantedAt: Long? = null,
    val weights: RecommendationPersonalization.ScoreWeights = RecommendationPersonalization.ScoreWeights()
) {
    fun shouldOfferSharedLearning(nowEpochMs: Long): Boolean = !sharedLearningConsent &&
        RecommendationConsentPolicy.isEligible(
            RecommendationConsentPolicy.State(
                meaningfulInteractions,
                recommendationPlaybackStarts,
                consentPromptCount,
                lastConsentDeclinedAt
            ),
            nowEpochMs
        )
}

/** Small local settings store; it never reads Android identifiers or account data. */
class RecommendationSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("recommendation_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<RecommendationSettings> = _state.asStateFlow()

    fun setLocalPersonalizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL, enabled).apply()
        _state.value = _state.value.copy(localPersonalizationEnabled = enabled)
    }

    fun resetAdaptedWeights() {
        val defaults = RecommendationPersonalization.ScoreWeights()
        prefs.edit().remove(KEY_WEIGHTS).apply()
        _state.value = _state.value.copy(weights = defaults)
    }

    fun recordMeaningfulInteraction() = updateCounters(interactionsDelta = 1, playbackDelta = 0)

    fun recordRecommendationPlaybackStart() = updateCounters(interactionsDelta = 1, playbackDelta = 1)

    fun declineSharedLearning(nowEpochMs: Long) {
        prefs.edit()
            .putInt(KEY_PROMPT_COUNT, _state.value.consentPromptCount + 1)
            .putLong(KEY_DECLINED_AT, nowEpochMs)
            .apply()
        _state.value = _state.value.copy(
            consentPromptCount = _state.value.consentPromptCount + 1,
            lastConsentDeclinedAt = nowEpochMs
        )
    }

    fun setSharedLearningConsent(granted: Boolean, nowEpochMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean(KEY_SHARED_CONSENT, granted)
            .apply {
                if (granted) putLong(KEY_GRANTED_AT, nowEpochMs) else remove(KEY_GRANTED_AT)
            }
            .apply()
        _state.value = _state.value.copy(
            sharedLearningConsent = granted,
            consentGrantedAt = nowEpochMs.takeIf { granted }
        )
    }

    private fun updateCounters(interactionsDelta: Int, playbackDelta: Int) {
        val interactions = _state.value.meaningfulInteractions + interactionsDelta
        val starts = _state.value.recommendationPlaybackStarts + playbackDelta
        prefs.edit().putInt(KEY_INTERACTIONS, interactions).putInt(KEY_PLAYBACK_STARTS, starts).apply()
        _state.value = _state.value.copy(
            meaningfulInteractions = interactions,
            recommendationPlaybackStarts = starts
        )
    }

    private fun load() = RecommendationSettings(
        localPersonalizationEnabled = prefs.getBoolean(KEY_LOCAL, true),
        sharedLearningConsent = prefs.getBoolean(KEY_SHARED_CONSENT, false),
        meaningfulInteractions = prefs.getInt(KEY_INTERACTIONS, 0),
        recommendationPlaybackStarts = prefs.getInt(KEY_PLAYBACK_STARTS, 0),
        consentPromptCount = prefs.getInt(KEY_PROMPT_COUNT, 0),
        lastConsentDeclinedAt = prefs.getLong(KEY_DECLINED_AT, -1L).takeIf { it >= 0L },
        consentGrantedAt = prefs.getLong(KEY_GRANTED_AT, -1L).takeIf { it >= 0L }
    )

    private companion object {
        const val KEY_LOCAL = "local_personalization"
        const val KEY_WEIGHTS = "adapted_weights"
        const val KEY_SHARED_CONSENT = "shared_learning_consent_v1"
        const val KEY_INTERACTIONS = "meaningful_interactions"
        const val KEY_PLAYBACK_STARTS = "recommendation_playback_starts"
        const val KEY_PROMPT_COUNT = "consent_prompt_count"
        const val KEY_DECLINED_AT = "consent_declined_at"
        const val KEY_GRANTED_AT = "consent_granted_at"
    }
}
