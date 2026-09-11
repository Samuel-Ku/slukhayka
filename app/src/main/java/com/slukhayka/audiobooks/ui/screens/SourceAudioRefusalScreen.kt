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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.source.SourceAudioRefusal
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import kotlinx.coroutines.launch

/**
 * The catalogued sources a listener may refuse, in display order. Spec-47 T5
 * registration extends this list in the SAME commit that registers a source
 * (ADR-0037's invariant: every catalogued AUDIO source is refusable — a
 * registration that forgets it leaves the source un-refusable). Spec-49 T5
 * adds a second home for the same list: the `source_refusals` rules
 * allowlist — a source missing there can never publish its counter.
 */
internal val REFUSABLE_SOURCES = listOf("sluhayua", "soundbooks", "audiobookmp3", "lihtar", "audiobookcoua", "chytaylo", "ukrainianaudiobooks", "knigionline", "chitaka")

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
 *
 * Spec-49 T5 — the same screen hosts the voluntary publish consent and the
 * anonymous shared badge. The consent defaults to off and revoking it stops
 * contributions; the badge renders only known positive counts and never
 * influences order, filters or visibility anywhere. The shared base is
 * best-effort throughout: a missing store, a missing profile or a failure
 * keeps the local flow working with no badge.
 */
@Composable
fun SourceAudioRefusalScreen(
    prefs: SourceAudioRefusal,
    sharedStore: SharedBookMetaStore?,
    uidProvider: suspend () -> String?,
    onBackClick: () -> Unit
) {
    val refused by prefs.refusedSources.collectAsState()
    val publishConsented by prefs.publishRefusals.collectAsState()
    val scope = rememberCoroutineScope()
    var sharedCounts by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    fun publish(sourceId: String) {
        val store = sharedStore ?: return
        scope.launch {
            val uid = uidProvider() ?: return@launch
            if (store.publishRefusalVote(sourceId, uid)) {
                sharedCounts = store.getRefusalCounts(REFUSABLE_SOURCES)
            }
        }
    }

    fun toggle(sourceId: String, checked: Boolean) {
        if (checked) prefs.refuse(sourceId) else prefs.allow(sourceId)
        if (checked && publishConsented) publish(sourceId)
    }

    fun toggleConsent(enabled: Boolean) {
        prefs.setPublishRefusals(enabled)
        if (enabled) {
            val store = sharedStore ?: return
            scope.launch {
                val uid = uidProvider() ?: return@launch
                refused.forEach { store.publishRefusalVote(it, uid) }
                sharedCounts = store.getRefusalCounts(REFUSABLE_SOURCES)
            }
        }
    }

    LaunchedEffect(Unit) {
        sharedCounts = sharedStore?.getRefusalCounts(REFUSABLE_SOURCES) ?: emptyMap()
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

            SourcePublishConsentRow(
                checked = publishConsented,
                onCheckedChange = ::toggleConsent
            )

            Spacer(modifier = Modifier.height(16.dp))

            REFUSABLE_SOURCES.forEach { sourceId ->
                SourceRefusalRow(
                    tag = "source_audio_refusal_${sourceId}_checkbox",
                    countTag = "source_audio_refusal_${sourceId}_shared_count",
                    label = sourceDisplayName(sourceId),
                    checked = sourceId in refused,
                    sharedCount = sharedCounts[sourceId],
                    onCheckedChange = { checked -> toggle(sourceId, checked) }
                )
            }
        }
    }
}

@Composable
private fun SourcePublishConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("source_audio_refusal_publish_checkbox")
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = stringResource(R.string.source_audio_refusal_publish_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
            )
            Text(
                text = stringResource(R.string.source_audio_refusal_publish_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SourceRefusalRow(
    tag: String,
    countTag: String,
    label: String,
    checked: Boolean,
    sharedCount: Long?,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
            )
            if (sharedCount != null && sharedCount > 0) {
                Text(
                    text = stringResource(R.string.source_audio_refusal_shared_count, sharedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(countTag)
                )
            }
        }
    }
}
