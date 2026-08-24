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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.RecommendationPreferenceEntity
import com.slukhayka.audiobooks.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationSettingsScreen(viewModel: MainViewModel, onBackClick: () -> Unit) {
    val settings by viewModel.recommendationSettings.collectAsState()
    val preferences by viewModel.recommendationPreferences.collectAsState()
    val catalog by viewModel.sourceCatalog.unifiedCatalog.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Персональні рекомендації") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Навчатися лише на цьому пристрої", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Обрані книги, прогрес і ваші відмови не залишають пристрій.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.localPersonalizationEnabled,
                            onCheckedChange = viewModel.recommendationPersonalization::setLocalEnabled
                        )
                    }
                }
            }
            item {
                SettingsCard {
                    Text("Допомагати покращувати спільну модель", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Ваша згода стосується лише майбутніх тижневих оновлень п’яти ваг. Передавання технічно вимкнене до перевірки приватності, безпеки та юридичних умов; книги й історія не надсилатимуться.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = settings.sharedLearningConsent,
                            onCheckedChange = viewModel.recommendationPersonalization::setSharedLearningConsent
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (settings.sharedLearningConsent) "Згоду збережено локально" else "Не беру участі",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            if (preferences.isNotEmpty()) {
                item { Text("Приховане вами", style = MaterialTheme.typography.titleMedium) }
                items(preferences, key = { "${it.kind}:${it.targetKey}" }) { preference ->
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(feedbackLabel(preference.kind), fontWeight = FontWeight.Medium)
                                Text(
                                    catalog.firstOrNull { it.key == preference.sourceWorkId }?.let { result ->
                                        if (preference.kind == RecommendationPreferenceEntity.HIDE_AUTHOR) {
                                            result.author
                                        } else {
                                            result.title
                                        }
                                    } ?: preference.targetKey,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        viewModel.recommendationPersonalization.remove(preference)
                                    }
                                }
                            }) { Text("Повернути") }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Скинути персоналізацію")
                }
                Text(
                    "Скидання видалить лише явні налаштування рекомендацій і локально адаптовані ваги. Книги, прогрес та історія відтворення залишаться.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Скинути персоналізацію?") },
            text = { Text("Вашу медіатеку та прогрес не буде видалено.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { viewModel.recommendationPersonalization.reset() }
                    }
                    confirmReset = false
                }) { Text("Скинути") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Скасувати") } }
        )
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
