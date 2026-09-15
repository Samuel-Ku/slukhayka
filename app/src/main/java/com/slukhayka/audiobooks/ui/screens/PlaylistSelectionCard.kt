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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.ingest.ListenerSubmissionFlow
import com.slukhayka.audiobooks.data.ingest.PreviewRunState

/**
 * Spec-53 T11 — the playlist preview's selection: ordered positions with
 * checkboxes (all picked by default), one tap for "add all", and the two
 * build modes. One book (chapters) is the default; separate books walks each
 * picked position through the ordinary single-video door with visible
 * progress and an honest stop.
 */
data class PlaylistSelectionState(
    val selected: Set<String>,
    val separateBooks: Boolean,
    val run: PreviewRunState = PreviewRunState()
)

data class PlaylistSelectionCallbacks(
    val onToggleEntry: (String) -> Unit,
    val onSelectAll: () -> Unit,
    val onSetSeparateBooks: (Boolean) -> Unit,
    val onAdd: (ListenerSubmissionFlow.PreviewEdits) -> Unit,
    val onStop: () -> Unit
)

@Composable
fun PlaylistSelectionCard(
    entries: List<ListenerSubmissionFlow.PreviewEntry>,
    state: PlaylistSelectionState,
    edits: ListenerSubmissionFlow.PreviewEdits,
    callbacks: PlaylistSelectionCallbacks,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("playlist_selection_card")
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        // The two build modes: one book of chapters (default) or separate books.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton(
                selected = !state.separateBooks,
                enabled = !state.run.running,
                onClick = { callbacks.onSetSeparateBooks(false) },
                testTag = "playlist_mode_one_book",
                text = stringResource(R.string.submission_playlist_one_book)
            )
            ModeButton(
                selected = state.separateBooks,
                enabled = !state.run.running,
                onClick = { callbacks.onSetSeparateBooks(true) },
                testTag = "playlist_mode_separate_books",
                text = stringResource(R.string.submission_playlist_separate)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.submission_playlist_selected,
                    state.selected.size,
                    entries.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .testTag("playlist_selected_count")
            )
            TextButton(
                onClick = callbacks.onSelectAll,
                enabled = !state.run.running,
                modifier = Modifier.testTag("playlist_select_all")
            ) {
                Text(stringResource(R.string.submission_playlist_select_all))
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .testTag("playlist_entry_list")
        ) {
            items(entries, key = { it.watchUrl }) { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = entry.watchUrl in state.selected,
                        onCheckedChange = { callbacks.onToggleEntry(entry.watchUrl) },
                        enabled = !state.run.running,
                        modifier = Modifier.testTag("playlist_entry_${entry.watchUrl}")
                    )
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    entry.durationSeconds?.takeIf { it > 0 }?.let { seconds ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatEntryDuration(seconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        val run = state.run
        if (run.running && run.progress != null) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    run.progress.done.toFloat() / run.progress.total.coerceAtLeast(1).toFloat()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("playlist_progress_bar")
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.submission_channel_progress,
                        run.progress.done,
                        run.progress.total,
                        run.progress.currentTitle
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("playlist_progress")
                )
                TextButton(
                    onClick = callbacks.onStop,
                    modifier = Modifier.testTag("playlist_stop")
                ) {
                    Text(stringResource(R.string.submission_channel_stop))
                }
            }
        }
        if (run.added != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (run.stopped) {
                    stringResource(R.string.submission_channel_stopped, run.added, run.total)
                } else {
                    stringResource(R.string.submission_channel_done, run.added, run.total)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("playlist_done")
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { callbacks.onAdd(edits) },
            enabled = state.selected.isNotEmpty() && !run.running,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("submission_preview_add")
        ) {
            Text(
                stringResource(R.string.submission_channel_add_selected, state.selected.size)
            )
        }
    }
}

@Composable
private fun ModeButton(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    text: String
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(testTag)) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(testTag)) {
            Text(text)
        }
    }
}

/** H:MM:SS, or M:SS below an hour — the engine's observed duration. */
private fun formatEntryDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:%02d:%02d".format(minutes, seconds)
    } else {
        "$minutes:%02d".format(seconds)
    }
}
