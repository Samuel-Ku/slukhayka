package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.facets.ContentLanguagePrefs
import com.slukhayka.audiobooks.ui.MainViewModel

/**
 * Spec-45 (#405) T6 (#494) — the «Мови контенту» destination: one checkbox
 * per known content language, both checked = «Усі» (US6). Every write goes
 * straight to the SAME persisted store the feed Pager and the SourceCatalog
 * surfaces read — no draft state, no separate save step; the Огляд «Мова»
 * chip writes the identical preference (US7/US8). "Both off" is impossible:
 * the last checked language cannot be unchecked (the store enforces the same
 * invariant on any direct write).
 */
@Composable
fun ContentLanguageScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit
) {
    val contentLanguages by viewModel.contentLanguages.collectAsState()

    fun toggle(language: String, checked: Boolean) {
        val current = contentLanguages
        val next = if (checked) current + language else current - language
        // Guard against the impossible state rather than letting the store
        // snap both back on underneath a one-checkbox-off click.
        if (next.isNotEmpty()) viewModel.setContentLanguages(next)
    }

    SettingsDestinationScaffold(
        destination = SettingsDestination.ContentLanguages,
        onBackClick = onBackClick
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("content_languages_screen")
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.content_languages_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            ContentLanguageRow(
                tag = "content_language_uk_checkbox",
                label = stringResource(R.string.content_language_uk),
                checked = "uk" in contentLanguages,
                onCheckedChange = { toggle("uk", it) }
            )
            ContentLanguageRow(
                tag = "content_language_en_checkbox",
                label = stringResource(R.string.content_language_en),
                checked = "en" in contentLanguages,
                onCheckedChange = { toggle("en", it) }
            )
        }
    }
}

@Composable
private fun ContentLanguageRow(
    tag: String,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .padding(vertical = 4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
        )
    }
}
