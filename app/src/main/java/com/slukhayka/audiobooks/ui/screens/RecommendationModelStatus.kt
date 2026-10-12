package com.slukhayka.audiobooks.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.recommend.RecommendationModelMode

@Composable
internal fun recommendationRuntimeModeText(
    mode: RecommendationModelMode
): String = stringResource(
    when (mode) {
        RecommendationModelMode.FULL -> R.string.recommendations_model_full
        RecommendationModelMode.SIMPLIFIED -> R.string.recommendations_model_simplified
        else -> R.string.recommendations_model_not_loaded
    }
)
