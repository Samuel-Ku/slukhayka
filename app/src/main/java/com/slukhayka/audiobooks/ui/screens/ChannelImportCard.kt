package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.ingest.ChannelCardState
import com.slukhayka.audiobooks.data.ingest.ChannelItemKind
import com.slukhayka.audiobooks.data.ingest.ChannelSelectionPolicy
import com.slukhayka.audiobooks.data.ingest.ChannelTab

/**
 * Spec-53 T10 — the whole-channel selection card: tabs, checkboxes,
 * «останні N», membership dedup, a paced run with progress and stop.
 * Everything shown comes from the engine read; the checked rows resolve
 * through [ChannelSelectionPolicy] right here, so the count on the add
 * button is exactly what the run will submit.
 */
data class ChannelCardCallbacks(
    val onClose: () -> Unit,
    val onRetryLoad: () -> Unit,
    val onTabSelect: (ChannelTab) -> Unit,
    val onLoadMore: () -> Unit,
    val onToggleItem: (String) -> Unit,
    val onSelectLastN: (Int) -> Unit,
    val onToggleIncludeSkipped: () -> Unit,
    val onStartImport: () -> Unit,
    val onStopImport: () -> Unit
)

@Composable
fun ChannelImportCard(
    state: ChannelCardState,
    callbacks: ChannelCardCallbacks,
    modifier: Modifier = Modifier
) {
    val resolved = remember(
        state.items,
        state.checkedIds,
        state.playlistMembers,
        state.includeSkipped
    ) {
        ChannelSelectionPolicy.resolve(
            state.items.filter { it.id in state.checkedIds },
            state.playlistMembers,
            state.includeSkipped
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("channel_card")
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.title.ifBlank { stringResource(R.string.submission_channel_title_default) },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
                    .testTag("channel_card_title")
            )
            TextButton(
                onClick = callbacks.onClose,
                modifier = Modifier.testTag("channel_card_close")
            ) {
                Text(stringResource(R.string.submission_channel_close))
            }
        }
        if (state.loading && state.items.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("channel_loading")
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.submission_channel_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (state.loadFailed && state.items.isEmpty()) {
            Text(
                text = stringResource(R.string.submission_channel_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("channel_failed")
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = callbacks.onRetryLoad,
                modifier = Modifier.testTag("channel_retry")
            ) {
                Text(stringResource(R.string.submission_channel_retry))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelTabButton(
                    selected = state.tab == ChannelTab.VIDEOS,
                    enabled = !state.running && !state.loading,
                    onClick = { callbacks.onTabSelect(ChannelTab.VIDEOS) },
                    testTag = "channel_tab_videos",
                    text = stringResource(R.string.submission_channel_videos)
                )
                ChannelTabButton(
                    selected = state.tab == ChannelTab.PLAYLISTS,
                    enabled = !state.running && !state.loading,
                    onClick = { callbacks.onTabSelect(ChannelTab.PLAYLISTS) },
                    testTag = "channel_tab_playlists",
                    text = stringResource(R.string.submission_channel_playlists)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (state.items.isEmpty()) {
                Text(
                    text = stringResource(R.string.submission_channel_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("channel_empty")
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .testTag("channel_item_list")
                ) {
                    items(state.items, key = { it.id }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = item.id in state.checkedIds,
                                onCheckedChange = { callbacks.onToggleItem(item.id) },
                                enabled = !state.running,
                                modifier = Modifier.testTag("channel_item_${item.id}")
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = channelItemSubtitle(item.kind, item.durationSeconds),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (item.id in state.membersLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .testTag("channel_members_loading_${item.id}")
                                )
                            }
                        }
                    }
                    if (state.hasMore && !state.loading) {
                        item(key = "more") {
                            TextButton(
                                onClick = callbacks.onLoadMore,
                                enabled = !state.running,
                                modifier = Modifier.testTag("channel_more")
                            ) {
                                Text(stringResource(R.string.submission_channel_more))
                            }
                        }
                    }
                }
                if (state.loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("channel_loading_more")
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.submission_channel_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.submission_channel_last_n),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    listOf(5, 10, 20).forEach { n ->
                        TextButton(
                            onClick = { callbacks.onSelectLastN(n) },
                            enabled = !state.running,
                            modifier = Modifier.testTag("channel_lastn_$n")
                        ) {
                            Text("$n")
                        }
                    }
                }
            }
            if (resolved.skippedVideoIds.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.submission_channel_skipped,
                            resolved.skippedVideoIds.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("channel_skipped")
                    )
                    TextButton(
                        onClick = callbacks.onToggleIncludeSkipped,
                        enabled = !state.running,
                        modifier = Modifier.testTag("channel_include_skipped")
                    ) {
                        Text(stringResource(R.string.submission_channel_add_skipped))
                    }
                }
            }
            val progress = state.progress
            if (state.running && progress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("channel_progress_bar")
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.submission_channel_progress,
                            progress.done,
                            progress.total,
                            progress.currentTitle
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("channel_progress")
                    )
                    TextButton(
                        onClick = callbacks.onStopImport,
                        modifier = Modifier.testTag("channel_stop")
                    ) {
                        Text(stringResource(R.string.submission_channel_stop))
                    }
                }
            }
            if (state.doneAdded != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.doneStopped) {
                        stringResource(
                            R.string.submission_channel_stopped,
                            state.doneAdded,
                            state.doneTotal
                        )
                    } else {
                        stringResource(
                            R.string.submission_channel_done,
                            state.doneAdded,
                            state.doneTotal
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("channel_done")
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = callbacks.onStartImport,
                enabled = resolved.toAdd.isNotEmpty() && !state.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("channel_add_selected")
            ) {
                Text(
                    stringResource(
                        R.string.submission_channel_add_selected,
                        resolved.toAdd.size
                    )
                )
            }
        }
    }
}

@Composable
private fun ChannelTabButton(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    text: String
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.testTag(testTag)
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.testTag(testTag)
        ) {
            Text(text)
        }
    }
}

@Composable
private fun channelItemSubtitle(kind: ChannelItemKind, durationSeconds: Long?): String =
    when (kind) {
        ChannelItemKind.PLAYLIST -> stringResource(R.string.submission_channel_playlist)
        ChannelItemKind.VIDEO -> durationSeconds?.takeIf { it > 0 }?.let(::formatChannelDuration).orEmpty()
    }

/** H:MM:SS, or M:SS below an hour — the engine's observed duration, nothing more. */
private fun formatChannelDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:%02d:%02d".format(minutes, seconds)
    } else {
        "$minutes:%02d".format(seconds)
    }
}
