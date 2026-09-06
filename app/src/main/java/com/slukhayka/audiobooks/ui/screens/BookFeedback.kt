package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.reviews.BookFeedbackDraft
import com.slukhayka.audiobooks.ui.BookFeedbackState
import com.slukhayka.audiobooks.ui.BookFeedbackController
import com.slukhayka.audiobooks.data.reviews.BookFeedbackStore

@Composable
internal fun BookFeedbackEntry(onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("book_feedback_entry"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.feedback_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.feedback_invitation), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_feedback_open")) {
            Text(stringResource(R.string.feedback_open))
        }
    }
}

@Composable
internal fun BookFeedbackHost(controller: BookFeedbackController, local: BookFeedbackStore, foreground: Boolean, allowAutomatic: Boolean) {
    val state by controller.state.collectAsState()
    val pending by local.pending.collectAsState()
    LaunchedEffect(foreground, allowAutomatic, pending, state?.bookId) {
        if (foreground && allowAutomatic && state == null) pending.firstOrNull()?.let { controller.open(it, automatic = true) }
    }
    if (foreground) state?.let { current ->
        BookFeedbackSheet(current, controller::edit, controller::save, controller::dismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookFeedbackSheet(
    state: BookFeedbackState,
    onEdit: (BookFeedbackDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = { if (!state.saving) onDismiss() }
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp)
            .testTag("book_feedback_form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (state.automatic) R.string.feedback_finished else R.string.feedback_title),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(state.title, style = MaterialTheme.typography.titleMedium)
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.feedback_loading))
            } else if (state.accepted) {
                Text(stringResource(R.string.feedback_accepted), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else {
                Text(stringResource(R.string.feedback_book_rating), modifier = Modifier.semantics { heading() })
                ReviewStarsRow(state.draft.bookRating, interactive = !state.saving,
                    onRatingChange = { onEdit(state.draft.copy(bookRating = it)) },
                    modifier = Modifier.testTag("feedback_book_stars"))
                Text(stringResource(R.string.feedback_narration_rating, state.narrator.ifBlank { stringResource(R.string.feedback_unknown_narrator) }),
                    modifier = Modifier.semantics { heading() })
                ReviewStarsRow(state.draft.narrationRating, interactive = !state.saving && state.editionId != null,
                    onRatingChange = { onEdit(state.draft.copy(narrationRating = it)) },
                    modifier = Modifier.testTag("feedback_narration_stars"))
                if (state.editionId == null) Text(stringResource(R.string.feedback_no_edition))
                OutlinedTextField(value = state.draft.body, onValueChange = { onEdit(state.draft.copy(body = it)) },
                    enabled = !state.saving, label = { Text(stringResource(R.string.feedback_body)) },
                    minLines = 3, modifier = Modifier.fillMaxWidth().testTag("feedback_body"))
                if (state.draft.body.isNotBlank() && state.draft.bookRating == 0) Text(stringResource(R.string.feedback_need_book_rating))
                if (state.failed) Text(stringResource(R.string.feedback_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = onSave, enabled = !state.saving &&
                    (state.draft.bookRating in 1..5 || state.draft.narrationRating in 1..5) &&
                    (state.draft.body.isBlank() || state.draft.bookRating in 1..5),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("feedback_save")) {
                    Text(stringResource(if (state.failed) R.string.feedback_retry else R.string.feedback_save))
                }
            }
            TextButton(onClick = onDismiss, enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("feedback_dismiss")) {
                Text(stringResource(if (state.accepted) R.string.feedback_done else R.string.feedback_later))
            }
        }
    }
}
