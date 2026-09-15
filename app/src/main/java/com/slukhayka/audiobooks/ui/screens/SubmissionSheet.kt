package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.ingest.ChannelCardState
import com.slukhayka.audiobooks.data.ingest.ListenerSubmissionFlow
import com.slukhayka.audiobooks.data.ingest.SubmissionState
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.CatalogCoverImage
import com.slukhayka.audiobooks.ui.components.MetadataCorrectionDialog
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment

/**
 * Spec-601 T3/T5 — the «Надіслати посилання» surface state. One paste can
 * import a local copy and (only after a real playback event) publish it;
 * every other branch is an honest refusal or an honest unsupported verdict.
 */
sealed interface SubmissionUiState {
    data object Idle : SubmissionUiState
    data object Working : SubmissionUiState
    data class Imported(val publishable: Boolean) : SubmissionUiState

    /**
     * Spec-53 T6 — the link's copy is already in MY library: a friendly state
     * with one clear action, never a generic refusal.
     */
    data class AlreadyInLibrary(val bookId: String) : SubmissionUiState
    data object Published : SubmissionUiState
    data object MetadataPublished : SubmissionUiState

    /** Spec-53 T8 — the paste had no network and waits in the visible queue. */
    data object Deferred : SubmissionUiState

    /**
     * Spec-53 T12 — the copy really played, but the day's budget was gone:
     * the publication waits for tomorrow and needs no second playback.
     */
    data object DeferredPublication : SubmissionUiState
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
    onListen: (() -> Unit)? = null,
    /** Spec-53 T4 — a link arriving from a system share. */
    prefillUrl: String? = null,
    /** Spec-53 T4 — a supported link already in the clipboard (chip). */
    clipboardCandidate: String? = null,
    /** Spec-53 T6 — opens the already-owned book from the sheet. */
    onOpenBook: ((String) -> Unit)? = null,
    /** Spec-53 T8 — the visible offline queue and its two actions. */
    deferredLinks: List<SubmissionState> = emptyList(),
    onRetryDeferred: ((String) -> Unit)? = null,
    onRemoveDeferred: ((String) -> Unit)? = null,
    /** Spec-53 T9 — the optional pre-add preview and its add-with-edits door. */
    preview: ListenerSubmissionFlow.SubmissionPreview? = null,
    onPreview: ((String) -> Unit)? = null,
    onClearPreview: (() -> Unit)? = null,
    onSubmitWithEdits: ((String, ListenerSubmissionFlow.PreviewEdits) -> Unit)? = null,
    /** Spec-53 T10 — the whole-channel selection card and its doors. */
    channelCard: ChannelCardState? = null,
    isChannelLink: ((String) -> Boolean)? = null,
    onOpenChannel: ((String) -> Unit)? = null,
    channelCallbacks: ChannelCardCallbacks? = null,
    /** Spec-53 T11 — the playlist preview's selection and its doors. */
    playlistSelection: PlaylistSelectionState? = null,
    playlistCallbacks: PlaylistSelectionCallbacks? = null
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
            prefillUrl = prefillUrl,
            clipboardCandidate = clipboardCandidate,
            onOpenBook = onOpenBook,
            deferredLinks = deferredLinks,
            onRetryDeferred = onRetryDeferred,
            onRemoveDeferred = onRemoveDeferred,
            preview = preview,
            onPreview = onPreview,
            onClearPreview = onClearPreview,
            onSubmitWithEdits = onSubmitWithEdits,
            channelCard = channelCard,
            isChannelLink = isChannelLink,
            onOpenChannel = onOpenChannel,
            channelCallbacks = channelCallbacks,
            playlistSelection = playlistSelection,
            playlistCallbacks = playlistCallbacks,
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
    prefillUrl: String? = null,
    clipboardCandidate: String? = null,
    onOpenBook: ((String) -> Unit)? = null,
    deferredLinks: List<SubmissionState> = emptyList(),
    onRetryDeferred: ((String) -> Unit)? = null,
    onRemoveDeferred: ((String) -> Unit)? = null,
    preview: ListenerSubmissionFlow.SubmissionPreview? = null,
    onPreview: ((String) -> Unit)? = null,
    onClearPreview: (() -> Unit)? = null,
    onSubmitWithEdits: ((String, ListenerSubmissionFlow.PreviewEdits) -> Unit)? = null,
    /** Spec-53 T10 — the whole-channel selection card and its doors. */
    channelCard: ChannelCardState? = null,
    isChannelLink: ((String) -> Boolean)? = null,
    onOpenChannel: ((String) -> Unit)? = null,
    channelCallbacks: ChannelCardCallbacks? = null,
    /** Spec-53 T11 — the playlist preview's selection and its doors. */
    playlistSelection: PlaylistSelectionState? = null,
    playlistCallbacks: PlaylistSelectionCallbacks? = null,
    includePaneSemantics: Boolean = true
) {
    var url by rememberSaveable(prefillUrl) { mutableStateOf(prefillUrl.orEmpty()) }
    // Spec-53 T9 — the preview's local corrections; cleared with the card.
    var previewEdits by remember(preview?.url) {
        mutableStateOf<ListenerSubmissionFlow.PreviewEdits?>(null)
    }
    var showPreviewEdit by remember { mutableStateOf(false) }
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
            onValueChange = {
                url = it
                // Spec-53 T9 — a changed link invalidates the loaded card.
                if (preview != null) onClearPreview?.invoke()
                // Spec-53 T10 — same for the open channel card: it shows
                // ITS link, never the edited one.
                if (channelCard != null) channelCallbacks?.onClose?.invoke()
            },
            label = { Text(stringResource(R.string.submission_url_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("submission_url_field")
        )
        // Spec-53 T4 — one-tap paste when the clipboard already carries a
        // supported link (never a silent read: the chip IS the offer).
        if (clipboardCandidate != null && url.isBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { url = clipboardCandidate },
                modifier = Modifier.testTag("submission_clipboard_chip")
            ) {
                Text(stringResource(R.string.submission_paste_from_clipboard))
            }
        }
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
        // Spec-53 T6 — the already-owned copy is one tap away, not a refusal.
        if (state is SubmissionUiState.AlreadyInLibrary && onOpenBook != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onOpenBook(state.bookId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("submission_open_book")
            ) {
                Text(stringResource(R.string.submission_open_book))
            }
        }
        // Spec-53 T8 — the queue is visible, and every item is actionable.
        if (deferredLinks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.submission_deferred_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("submission_deferred_title")
            )
            deferredLinks.forEach { link ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("submission_deferred_${link.sourceId}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = link.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (onRetryDeferred != null) {
                        TextButton(
                            onClick = { onRetryDeferred(link.sourceId) },
                            modifier = Modifier.testTag("submission_deferred_retry_${link.sourceId}")
                        ) {
                            Text(stringResource(R.string.submission_deferred_retry))
                        }
                    }
                    if (onRemoveDeferred != null) {
                        TextButton(
                            onClick = { onRemoveDeferred(link.sourceId) },
                            modifier = Modifier.testTag("submission_deferred_remove_${link.sourceId}")
                        ) {
                            Text(stringResource(R.string.submission_deferred_remove))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        // Spec-53 T9 — the habitual path stays one tap («Додати одразу»);
        // the preview is the optional second door, never a tollbooth.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Spec-53 T10 — a channel link gets the selection card's door
            // instead of the single-book preview: a channel is picked,
            // never auto-imported.
            if (isChannelLink?.invoke(url) == true && channelCard == null && onOpenChannel != null) {
                OutlinedButton(
                    onClick = { onOpenChannel(url.trim()) },
                    enabled = url.isNotBlank() && state !is SubmissionUiState.Working,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("submission_channel_open")
                ) {
                    Text(stringResource(R.string.submission_channel_open))
                }
            } else if (onPreview != null) {
                OutlinedButton(
                    onClick = { onPreview(url.trim()) },
                    enabled = url.isNotBlank() && state !is SubmissionUiState.Working,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("submission_preview")
                ) {
                    Text(stringResource(R.string.submission_preview_action))
                }
            }
            Button(
                onClick = { onSubmit(url.trim()) },
                enabled = url.isNotBlank() && state !is SubmissionUiState.Working,
                modifier = Modifier
                    .weight(1f)
                    .testTag("submission_submit")
            ) {
                Text(stringResource(R.string.submission_submit_action))
            }
        }
        // Spec-53 T9 — the pre-add card: engine data only, editable, honest.
        if (preview != null) {
            val shownTitle = previewEdits?.title ?: preview.title
            val shownAuthor = previewEdits?.author ?: preview.author
            val shownNarrator = previewEdits?.narrator ?: preview.narrator
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("submission_preview_card"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CatalogCoverImage(
                    coverImageUrl = preview.coverUrl,
                    title = shownTitle,
                    semantics = BookCoverSemantics.Decorative,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = previewTypeText(preview),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.testTag("submission_preview_type")
                    )
                    Text(
                        text = shownTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("submission_preview_title")
                    )
                    if (!shownAuthor.isNullOrBlank()) {
                        Text(
                            text = shownAuthor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    preview.durationSeconds?.let { total ->
                        Text(
                            text = formatPreviewDuration(total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("submission_preview_duration")
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = { showPreviewEdit = true },
                    modifier = Modifier.testTag("submission_preview_edit")
                ) {
                    Text(stringResource(R.string.submission_preview_edit))
                }
                Spacer(modifier = Modifier.weight(1f))
                // Spec-53 T11 — a playlist with pickable positions gets the
                // selection card's own add button instead of the plain one.
                if (onSubmitWithEdits != null && preview.entries.isEmpty()) {
                    Button(
                        onClick = {
                            onSubmitWithEdits(
                                preview.url,
                                previewEdits ?: ListenerSubmissionFlow.PreviewEdits()
                            )
                        },
                        modifier = Modifier.testTag("submission_preview_add")
                    ) {
                        Text(stringResource(R.string.submission_preview_add))
                    }
                }
            }
            // Spec-53 T11 — ordered positions, "add all" by default, and the
            // one-book / separate-books choice.
            if (preview.entries.isNotEmpty() && playlistSelection != null && playlistCallbacks != null) {
                PlaylistSelectionCard(
                    entries = preview.entries,
                    state = playlistSelection,
                    edits = previewEdits ?: ListenerSubmissionFlow.PreviewEdits(),
                    callbacks = playlistCallbacks
                )
            }
        }
        if (showPreviewEdit && preview != null) {
            // Spec-53 T9 — the SAME form as the post-import fix (T7).
            MetadataCorrectionDialog(
                initialTitle = previewEdits?.title ?: preview.title,
                initialAuthor = previewEdits?.author ?: preview.author.orEmpty(),
                initialNarrator = previewEdits?.narrator ?: preview.narrator.orEmpty(),
                onDismiss = { showPreviewEdit = false },
                onSave = { title, author, narrator ->
                    previewEdits = ListenerSubmissionFlow.PreviewEdits(
                        title = title,
                        author = author,
                        narrator = narrator
                    )
                    showPreviewEdit = false
                }
            )
        }
        // Spec-53 T10 — the whole-channel selection card.
        if (channelCard != null && channelCallbacks != null) {
            ChannelImportCard(
                state = channelCard,
                callbacks = channelCallbacks
            )
        }
    }
}

@Composable
private fun previewTypeText(preview: ListenerSubmissionFlow.SubmissionPreview): String =
    when (preview.kind) {
        ListenerSubmissionFlow.PreviewKind.YOUTUBE_VIDEO ->
            stringResource(R.string.submission_preview_single_video)
        ListenerSubmissionFlow.PreviewKind.YOUTUBE_PLAYLIST ->
            stringResource(R.string.submission_preview_playlist_prefix) + " · " +
                pluralStringResource(
                    R.plurals.submission_preview_chapters,
                    preview.chapterCount,
                    preview.chapterCount
                )
        ListenerSubmissionFlow.PreviewKind.TELEGRAM_POST ->
            stringResource(R.string.submission_preview_telegram)
    }

/** H:MM:SS, or M:SS below an hour — the engine's observed total, nothing more. */
private fun formatPreviewDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:%02d:%02d".format(minutes, seconds)
    } else {
        "$minutes:%02d".format(seconds)
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
    is SubmissionUiState.AlreadyInLibrary ->
        stringResource(R.string.submission_status_already_in_library)
    SubmissionUiState.MetadataPublished -> stringResource(R.string.submission_status_metadata_published)
    SubmissionUiState.Deferred -> stringResource(R.string.submission_status_deferred)
    SubmissionUiState.DeferredPublication ->
        stringResource(R.string.submission_status_deferred_publication)
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
