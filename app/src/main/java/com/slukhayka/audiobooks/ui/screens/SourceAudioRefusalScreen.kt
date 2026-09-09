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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
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
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.source.SourceAudioRefusal
import com.slukhayka.audiobooks.data.source.sourceDisplayName

/** The catalogued sources a listener may refuse, in display order. */
internal val REFUSABLE_SOURCES = listOf("4read", "sluhayua", "soundbooks", "audiobookmp3", "lihtar", "audiobookcoua")

/**
 * ADR-0037 (spec-49 T1) — the «Аудіо джерел» destination: one checkbox per
 * catalogued source, checked = the listener refuses that source's AUDIO.
 * Every write goes straight to the SAME persisted store the selection
 * coordinator, the catalog pairing and the download gate read — no draft
 * state, no separate save step. The source itself stays a full metadata
 * source (sections, «Новинки», union, covers, durations): the refusal stops
 * audio only, and is reversible — unchecking wakes the dormant Source rows
 * with no re-import. The refusal is absolute: there is no browser escape
 * hatch for a refused source.
 */
@Composable
fun SourceAudioRefusalScreen(
    prefs: SourceAudioRefusal,
    onBackClick: () -> Unit
) {
    val refused by prefs.refusedSources.collectAsState()

    fun toggle(sourceId: String, checked: Boolean) {
        if (checked) prefs.refuse(sourceId) else prefs.allow(sourceId)
    }

    SettingsDestinationScaffold(
        destination = SettingsDestination.SourceAudioRefusal,
        onBackClick = onBackClick
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("source_audio_refusal_screen")
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.source_audio_refusal_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            REFUSABLE_SOURCES.forEach { sourceId ->
                SourceRefusalRow(
                    tag = "source_audio_refusal_${sourceId}_checkbox",
                    label = sourceDisplayName(sourceId),
                    checked = sourceId in refused,
                    onCheckedChange = { checked -> toggle(sourceId, checked) }
                )
            }
        }
    }
}

@Composable
private fun SourceRefusalRow(
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
