package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.RecommendationPreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Deep local module owning recommendation choices and their exact undo semantics. */
class RecommendationPreferences(
    private val dao: AudiobookDao,
    private val settingsStore: RecommendationSettingsStore
) {
    val preferences: Flow<List<RecommendationPreferenceEntity>> = dao.observeRecommendationPreferences()
    val settings: StateFlow<RecommendationSettings> = settingsStore.state

    data class UndoToken(
        val applied: RecommendationPreferenceEntity,
        val previous: RecommendationPreferenceEntity?
    )

    suspend fun applyFeedback(candidateId: String, author: String, kind: String): UndoToken? {
        val target = if (kind == RecommendationPreferenceEntity.HIDE_AUTHOR) {
            RecommendationPersonalization.identityKey(author)
        } else candidateId
        if (target.isBlank()) return null
        val preference = RecommendationPreferenceEntity(kind, target, candidateId)
        val previous = preferences.first().firstOrNull { it.kind == kind && it.targetKey == target }
        dao.upsertRecommendationPreference(preference)
        settingsStore.recordMeaningfulInteraction()
        return UndoToken(preference, previous)
    }

    suspend fun undo(token: UndoToken) {
        token.previous?.let { dao.upsertRecommendationPreference(it) }
            ?: dao.deleteRecommendationPreference(token.applied.kind, token.applied.targetKey)
    }

    suspend fun remove(preference: RecommendationPreferenceEntity) {
        dao.deleteRecommendationPreference(preference.kind, preference.targetKey)
    }

    fun setLocalEnabled(enabled: Boolean) = settingsStore.setLocalPersonalizationEnabled(enabled)
    fun declineSharedLearning() = settingsStore.declineSharedLearning(System.currentTimeMillis())
    fun setSharedLearningConsent(granted: Boolean) = settingsStore.setSharedLearningConsent(granted)
    fun recordDetailOpen() = settingsStore.recordMeaningfulInteraction()
    fun recordPlaybackStart() = settingsStore.recordRecommendationPlaybackStart()

    suspend fun reset() {
        dao.clearRecommendationPreferences()
        settingsStore.resetAdaptedWeights()
    }
}
