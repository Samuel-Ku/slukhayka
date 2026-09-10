package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.ingest.ListenerSubmissionFlow
import com.slukhayka.audiobooks.ui.components.accessibilityPane

/**
 * Spec-601 T3/T5 — the «Надіслати посилання» surface state. One paste can
 * import a local copy and (only after a real playback event) publish it;
 * every other branch is an honest refusal or an honest unsupported verdict.
 */
sealed interface SubmissionUiState {
    data object Idle : SubmissionUiState
    data object Working : SubmissionUiState
    data class Imported(val publishable: Boolean) : SubmissionUiState
    data object Published : SubmissionUiState
    data object MetadataPublished : SubmissionUiState
    data class Refused(val reason: ListenerSubmissionFlow.Reason) : SubmissionUiState
    data object Unsupported : SubmissionUiState
}

/**
 * The one «надіслати посилання» door: paste a YouTube video/playlist or a
 * Telegram post, submit, and follow the honest state. Publication happens
 * only after the imported copy really plays — the sheet says so plainly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmissionSheet(
    state: SubmissionUiState,
    remainingToday: Int?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    onListen: (() -> Unit)? = null
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.accessibilityPane(stringResource(R.string.a11y_submission_pane))
    ) {
        SubmissionSheetContent(
            state = state,
            remainingToday = remainingToday,
            onSubmit = onSubmit,
            onClose = onDismiss,
            onListen = onListen,
            includePaneSemantics = false
        )
    }
}

/** The sheet body, extracted so the snapshot/test seam never hosts a sheet window. */
@Composable
fun SubmissionSheetContent(
    state: SubmissionUiState,
    remainingToday: Int?,
    onSubmit: (String) -> Unit,
    onClose: (() -> Unit)? = null,
    onListen: (() -> Unit)? = null,
    includePaneSemantics: Boolean = true
) {
    var url by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (includePaneSemantics) {
                    Modifier.accessibilityPane(stringResource(R.string.a11y_submission_pane))
                } else Modifier
            )
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .testTag("submission_sheet_content")
    ) {
        Row {
            Text(
                text = stringResource(R.string.submission_sheet_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
                    .testTag("submission_sheet_heading")
            )
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(48.dp).testTag("submission_sheet_close")
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.a11y_submission_close))
                }
            }
        }
        Text(
            text = stringResource(R.string.submission_sheet_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.submission_url_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("submission_url_field")
        )
        if (remainingToday != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.submission_remaining, remainingToday),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("submission_remaining")
            )
        }
        submissionStatusText(state)?.let { status ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("submission_status")
            )
        }
        // Spec-53 T3 — the one explicit action after an import; no autoplay.
        if (state is SubmissionUiState.Imported && onListen != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onListen,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("submission_listen_now")
            ) {
                Text(stringResource(R.string.submission_listen_now))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(url.trim()) },
            enabled = url.isNotBlank() && state !is SubmissionUiState.Working,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("submission_submit")
        ) {
            Text(stringResource(R.string.submission_submit_action))
        }
    }
}

@Composable
private fun submissionStatusText(state: SubmissionUiState): String? = when (state) {
    SubmissionUiState.Idle -> null
    SubmissionUiState.Working -> stringResource(R.string.submission_status_working)
    is SubmissionUiState.Imported -> if (state.publishable) {
        stringResource(R.string.submission_status_imported)
    } else {
        stringResource(R.string.submission_status_imported_local)
    }
    SubmissionUiState.Published -> stringResource(R.string.submission_status_published)
    SubmissionUiState.MetadataPublished -> stringResource(R.string.submission_status_metadata_published)
    is SubmissionUiState.Refused -> stringResource(submissionRefusalRes(state.reason))
    SubmissionUiState.Unsupported -> stringResource(R.string.submission_status_unsupported)
}

private fun submissionRefusalRes(reason: ListenerSubmissionFlow.Reason): Int = when (reason) {
    ListenerSubmissionFlow.Reason.DAILY_LIMIT_REACHED -> R.string.submission_status_refused_limit
    ListenerSubmissionFlow.Reason.METADATA_FAILED -> R.string.submission_status_refused_metadata
    ListenerSubmissionFlow.Reason.NO_PLAYABLE_TRACKS -> R.string.submission_status_refused_no_tracks
    ListenerSubmissionFlow.Reason.IMPORT_FAILED -> R.string.submission_status_refused_import
    ListenerSubmissionFlow.Reason.ALREADY_PUBLISHED -> R.string.submission_status_refused_duplicate
    ListenerSubmissionFlow.Reason.NOT_VERIFIED -> R.string.submission_status_refused_not_verified
    ListenerSubmissionFlow.Reason.SHARED_BASE_UNAVAILABLE -> R.string.submission_status_refused_unavailable
}
