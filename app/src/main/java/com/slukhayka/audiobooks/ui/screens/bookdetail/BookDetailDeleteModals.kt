package com.slukhayka.audiobooks.ui.screens.bookdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.DownloadState
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.streamOnlyFor
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.library.siblingNarrations
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.ReviewSaveResult
import com.slukhayka.audiobooks.ui.bookPersonPath
import com.slukhayka.audiobooks.ui.reviewWorkIdFor
import com.slukhayka.audiobooks.ui.components.BookmarkDialog
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.components.PosterCard
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.displayNarrator
import com.slukhayka.audiobooks.ui.library.BookPlayState
import com.slukhayka.audiobooks.ui.library.bookPlayLabel
import com.slukhayka.audiobooks.ui.library.bookPlayState
import com.slukhayka.audiobooks.ui.library.bookPositionAndTotal
import com.slukhayka.audiobooks.ui.library.ukPlural
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
                icon = Icons.Default.RemoveCircleOutline,
                color = MaterialTheme.colorScheme.primary,
                testTag = "delete_remove_from_library",
                onClick = onRemoveFromLibrary
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (isDownloaded) {
                BookDeleteOption(
                    title = stringResource(R.string.a11y_book_detail_delete_download, workTitle),
                    consequence = stringResource(R.string.a11y_book_detail_delete_download_consequence),
                    icon = Icons.Default.CloudOff,
                    color = MaterialTheme.colorScheme.onSurface,
                    testTag = "delete_downloaded_copy",
                    onClick = onDeleteDownloadedCopy
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }

            BookDeleteOption(
                title = stringResource(R.string.a11y_book_detail_delete_everything, workTitle),
                consequence = stringResource(R.string.a11y_book_detail_delete_everything_consequence),
                icon = Icons.Default.Delete,
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

