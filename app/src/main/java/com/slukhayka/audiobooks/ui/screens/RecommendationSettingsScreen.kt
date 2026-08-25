package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.RecommendationPreferenceEntity
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationSettingsScreen(viewModel: MainViewModel, onBackClick: () -> Unit) {
    val settings by viewModel.recommendationSettings.collectAsState()
    val preferences by viewModel.recommendationPreferences.collectAsState()
    val catalog by viewModel.sourceCatalog.unifiedCatalog.collectAsState()
    var resetNotice by remember { mutableStateOf<String?>(null) }
    var resetDialogVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val screenTitle = stringResource(R.string.recommendations_title)
    val resetDoneMessage = stringResource(R.string.recommendations_reset_done)

    Scaffold(
        modifier = Modifier
            .accessibilityPane(screenTitle)
            .accessibilityModalBackground(resetDialogVisible),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        screenTitle,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsCard {
                    SettingsSwitchRow(
                        title = stringResource(R.string.recommendations_local_title),
                        description = stringResource(R.string.recommendations_local_description),
                        checked = settings.localPersonalizationEnabled,
                        onCheckedChange = viewModel.recommendationPersonalization::setLocalEnabled,
                        testTag = "recommendations_local_switch"
                    )
                }
            }
            item {
                SettingsCard {
                    SettingsSwitchRow(
                        title = stringResource(R.string.recommendations_shared_title),
                        description = stringResource(R.string.recommendations_shared_description),
                        checked = settings.sharedLearningConsent,
                        onCheckedChange = viewModel.recommendationPersonalization::setSharedLearningConsent,
                        testTag = "recommendations_shared_switch",
                        checkedStateDescription = stringResource(R.string.recommendations_shared_on),
                        uncheckedStateDescription = stringResource(R.string.recommendations_shared_off)
                    )
                }
            }
            if (preferences.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.recommendations_hidden_heading),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                }
                items(preferences, key = { "${it.kind}:${it.targetKey}" }) { preference ->
                    val targetName = catalog.firstOrNull { it.key == preference.sourceWorkId }?.let { result ->
                        if (preference.kind == RecommendationPreferenceEntity.HIDE_AUTHOR) {
                            result.author
                        } else {
                            result.title
                        }
                    } ?: preference.targetKey
                    val returnDescription = stringResource(
                        R.string.recommendations_restore_description,
                        targetName
                    )
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(feedbackLabel(preference.kind), fontWeight = FontWeight.Medium)
                                Text(
                                    targetName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            viewModel.recommendationPersonalization.remove(preference)
                                        }
                                    }
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = returnDescription
                                }
                            ) { Text(stringResource(R.string.action_restore)) }
                        }
                    }
                }
            }
            item {
                RecommendationResetControl(
                    onReset = {
                        resetNotice = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                viewModel.recommendationPersonalization.reset()
                            }
                            resetNotice = resetDoneMessage
                        }
                    },
                    onDialogVisibilityChange = { resetDialogVisible = it }
                )
                resetNotice?.let { notice ->
                    Text(
                        text = notice,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .testTag("recommendations_reset_notice")
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

private fun feedbackLabel(kind: String): String = when (kind) {
    RecommendationPreferenceEntity.HIDE_WORK -> "Не рекомендувати цю книгу"
    RecommendationPreferenceEntity.REDUCE_SIMILAR -> "Менше схожих"
    RecommendationPreferenceEntity.HIDE_AUTHOR -> "Не рекомендувати цього автора"
    else -> "Налаштування рекомендацій"
}
