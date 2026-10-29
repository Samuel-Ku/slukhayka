package com.slukhayka.audiobooks.ui.screens.bookdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.theme.*

@Composable
fun BookDeleteModalLifecycle(
    workTitle: String,
    isDownloaded: Boolean,
    showOptions: Boolean,
    showConfirmation: Boolean,
    returnFocusRequester: FocusRequester,
    onRemoveFromLibrary: () -> Unit,
    onDeleteDownloadedCopy: () -> Unit,
    onConfirmDelete: () -> Unit,
    onOptionsDismiss: () -> Unit,
    onRequestConfirmation: () -> Unit,
    onConfirmationDismiss: () -> Unit
) {
    RestoreFocusAfterModal(
        modalVisible = showOptions || showConfirmation,
        returnFocusRequester = returnFocusRequester
    )

    if (showOptions) {
        BookDeleteOptionsSheet(
            workTitle = workTitle,
            isDownloaded = isDownloaded,
            onRemoveFromLibrary = {
                onRemoveFromLibrary()
                onOptionsDismiss()
            },
            onDeleteDownloadedCopy = {
                onDeleteDownloadedCopy()
                onOptionsDismiss()
            },
            onDeleteEverything = {
                onOptionsDismiss()
                onRequestConfirmation()
            },
            onDismiss = onOptionsDismiss
        )
    }

    if (showConfirmation) {
        BookDeleteConfirmationDialog(
            workTitle = workTitle,
            onConfirm = {
                onConfirmDelete()
                onConfirmationDismiss()
            },
            onDismiss = onConfirmationDismiss
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BookDeleteOptionsSheet(
    workTitle: String,
    isDownloaded: Boolean,
    onRemoveFromLibrary: () -> Unit,
    onDeleteDownloadedCopy: () -> Unit,
    onDeleteEverything: () -> Unit,
    onDismiss: () -> Unit
) {
    val headingFocusRequester = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .accessibilityPane(
                stringResource(R.string.a11y_book_detail_delete_options_pane, workTitle)
            )
            .testTag("book_detail_delete_options_sheet")
    ) {
        LaunchedEffect(headingFocusRequester) {
            withFrameNanos { }
            headingFocusRequester.requestFocus()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.a11y_book_detail_delete_options_title, workTitle),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("book_detail_delete_options_heading")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.a11y_book_detail_delete_options_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            BookDeleteOption(
                title = stringResource(R.string.a11y_book_detail_remove_library, workTitle),
                consequence = stringResource(R.string.a11y_book_detail_remove_library_consequence),
                icon = Icons.Outlined.RemoveCircleOutline,
                color = MaterialTheme.colorScheme.primary,
                testTag = "delete_remove_from_library",
                onClick = onRemoveFromLibrary
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (isDownloaded) {
                BookDeleteOption(
                    title = stringResource(R.string.a11y_book_detail_delete_download, workTitle),
                    consequence = stringResource(R.string.a11y_book_detail_delete_download_consequence),
                    icon = PaperInkIcons.Offline,
                    color = MaterialTheme.colorScheme.onSurface,
                    testTag = "delete_downloaded_copy",
                    onClick = onDeleteDownloadedCopy
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }

            BookDeleteOption(
                title = stringResource(R.string.a11y_book_detail_delete_everything, workTitle),
                consequence = stringResource(R.string.a11y_book_detail_delete_everything_consequence),
                icon = PaperInkIcons.Trash,
                color = MaterialTheme.colorScheme.error,
                testTag = "delete_book_and_files",
                onClick = onDeleteEverything
            )
        }
    }
}

@Composable
private fun BookDeleteOption(
    title: String,
    consequence: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = consequence,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}

@Composable
fun BookDeleteConfirmationDialog(
    workTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val headingFocusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        // MD3: dialog = surfaceContainerHigh (highest tonal step of a
        // raised container, below text fields).
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .accessibilityPane(
                stringResource(R.string.a11y_book_detail_delete_confirm_pane, workTitle)
            )
            .testTag("book_detail_delete_confirm_dialog"),
        title = {
            LaunchedEffect(headingFocusRequester) {
                withFrameNanos { }
                headingFocusRequester.requestFocus()
            }
            Text(
                text = stringResource(R.string.a11y_book_detail_delete_confirm_title, workTitle),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("book_detail_delete_confirm_heading")
            )
        },
        text = {
            Text(
                text = stringResource(R.string.a11y_book_detail_delete_confirm_consequence, workTitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("book_detail_delete_confirm")
            ) {
                Text(
                    stringResource(R.string.book_detail_bookmark_delete_confirm),
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(R.string.book_detail_cancel),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}

/**
 * v1.4 E4 / spec-46 T11 (#572) — the narration-rating delete confirmation.
 * Extracted verbatim from [BookDetailScreen]: the same pane announcement,
 * the same heading focus contract and the same 48 dp actions, but the
 * Ukrainian chrome now comes from resources so the EN run has no Cyrillic.
 */
@Composable
fun NarrationRatingDeleteConfirmation(
    narrator: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val titleFocusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .accessibilityPane(stringResource(R.string.book_detail_narration_rating_delete_pane))
            .testTag("narration_rating_delete_dialog"),
        title = {
            Text(
                stringResource(R.string.book_detail_narration_rating_delete_title),
                modifier = Modifier
                    .focusRequester(titleFocusRequester)
                    .focusable()
                    .semantics { heading() }
            )
            LaunchedEffect(Unit) {
                withFrameNanos { }
                titleFocusRequester.requestFocus()
            }
        },
        text = {
            Text(
                stringResource(
                    R.string.book_detail_narration_rating_delete_body,
                    narrator
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minHeight = 48.dp)
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
fun BookmarkDeleteConfirmation(
    workTitle: String,
    bookmark: BookmarkEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val timestamp = MainViewModel.formatTime(bookmark.timestampSeconds)
    val headingFocusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .accessibilityPane(stringResource(R.string.book_detail_bookmark_delete_pane))
            .testTag("book_detail_bookmark_delete_dialog"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            LaunchedEffect(headingFocusRequester) {
                withFrameNanos { }
                headingFocusRequester.requestFocus()
            }
            Text(
                text = stringResource(R.string.book_detail_bookmark_delete_title),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("book_detail_bookmark_delete_heading")
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.book_detail_bookmark_delete_question,
                        workTitle,
                        bookmark.chapterTitle,
                        timestamp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (bookmark.note.isBlank()) {
                        stringResource(R.string.book_detail_bookmark_delete_consequence_no_note)
                    } else {
                        stringResource(
                            R.string.book_detail_bookmark_delete_consequence,
                            bookmark.note
                        )
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("book_detail_bookmark_delete_confirm")
            ) {
                Text(
                    stringResource(R.string.book_detail_bookmark_delete_confirm),
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(R.string.book_detail_cancel),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}

