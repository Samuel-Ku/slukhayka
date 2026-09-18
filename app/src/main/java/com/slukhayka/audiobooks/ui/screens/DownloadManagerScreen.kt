package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.downloads.DownloadQueue
import com.slukhayka.audiobooks.data.downloads.DownloadQueueItem
import com.slukhayka.audiobooks.data.downloads.DownloadQueueStatus
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.library.downloadMemorySummaryText
import com.slukhayka.audiobooks.ui.library.downloadQueueDetailText
import com.slukhayka.audiobooks.ui.library.removeCompletedConfirmText

/**
 * #899 — the download manager, opened from the «Завантаження та пам'ять»
 * settings row (spec-28 #194 kept the row, the owner decision moved what it
 * opens here). It reads the EXISTING download state and adds no entity: the
 * queue is the pure [DownloadQueue] projection of the Library Entries, the
 * per-book Source Track counts and the persisted recovery flag; the memory
 * summary is the audio cache's occupied size plus the volume's free space.
 *
 * The device-memory tools of the old destination — the rescan and the
 * destructive delete-all — are NOT dropped (docs/specs §9: «Зберегти
 * інструменти»): they stay in the same screen, reusing
 * [StorageDestinationPane] below the queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit
) {
    val libraryBooks by viewModel.libraryBooks.collectAsState()
    val queue by viewModel.downloadQueue.collectAsState()
    val cacheSizeFormatted by viewModel.cacheSizeFormatted.collectAsState()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsState()
    val freeBytes by viewModel.downloadFreeBytes.collectAsState()
    var storageDialogVisible by remember { mutableStateOf(false) }

    // The queue's own facts are reactive (Room + the active job); the
    // filesystem ones are not. Re-read them whenever an item's status or
    // chapter count changes — and on first open, when the library flow has
    // not emitted yet (the next emission changes the signature and re-reads).
    val queueSignature = queue.map { "${it.bookId}:${it.status}:${it.downloadedChapters}" }
    LaunchedEffect(queueSignature) {
        viewModel.refreshCacheSize()
        viewModel.refreshDownloadQueue()
    }

    val offlineCount = libraryBooks.count { it.book.isDownloaded }
    val hasLocalBooks = libraryBooks.any { it.isLocal }

    SettingsDestinationScaffold(
        destination = SettingsDestination.Storage,
        onBackClick = onBackClick,
        modalVisible = storageDialogVisible
    ) { padding ->
        DownloadManagerPane(
            items = queue,
            storageText = downloadMemorySummaryText(cacheSizeBytes, freeBytes, offlineCount),
            hasLocalBooks = hasLocalBooks,
            showDelete = offlineCount > 0 || cacheSizeBytes > 0L,
            bookCount = offlineCount,
            bytes = cacheSizeBytes,
            onPause = viewModel::pauseDownload,
            onContinue = viewModel::continueDownload,
            onCancel = viewModel::cancelDownload,
            onRemove = viewModel::removeDownload,
            onRemoveCompleted = viewModel::removeCompletedDownloads,
            onRescan = { viewModel.rescanLocalFolders() },
            onDeleteAllConfirmed = viewModel::clearAllAudioCache,
            onStorageDialogVisibilityChange = { storageDialogVisible = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

/**
 * The stateful manager body, shared by production and Compose behaviour
 * tests: pure inputs, no `MainViewModel`. The queue owns the flexible space;
 * the existing storage pane keeps its fixed tools at the bottom.
 *
 * Two different stopping actions, deliberately (owner follow-up on #899):
 * [onCancel] stops a queue and KEEPS every file on disk, [onRemove] destroys
 * the copy and therefore always asks first.
 */
@Composable
fun DownloadManagerPane(
    items: List<DownloadQueueItem>,
    storageText: String,
    hasLocalBooks: Boolean,
    showDelete: Boolean,
    bookCount: Int,
    bytes: Long,
    onPause: (String) -> Unit,
    onContinue: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRemoveCompleted: () -> Unit,
    onRescan: () -> Unit,
    onDeleteAllConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    onStorageDialogVisibilityChange: (Boolean) -> Unit = {}
) {
    var pendingRemoval by remember { mutableStateOf<DownloadQueueItem?>(null) }
    var removeCompletedVisible by remember { mutableStateOf(false) }
    val completedIds = remember(items) { DownloadQueue.completedBookIds(items) }
    val managerDialogVisible = pendingRemoval != null || removeCompletedVisible

    Column(modifier = modifier.accessibilityModalBackground(managerDialogVisible)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (items.isEmpty()) {
                DownloadQueueEmptyState(modifier = Modifier.fillMaxSize())
            } else {
                DownloadQueueList(
                    items = items,
                    completedCount = completedIds.size,
                    onPause = onPause,
                    onContinue = onContinue,
                    onCancel = onCancel,
                    onRemove = { pendingRemoval = it },
                    onRemoveCompleted = { removeCompletedVisible = true }
                )
            }
        }

        // spec-28 (#194) tools, unchanged: summary card, local rescan and the
        // destructive delete behind its exact-scope confirmation.
        StorageDestinationPane(
            storageText = storageText,
            hasLocalBooks = hasLocalBooks,
            showDelete = showDelete,
            bookCount = bookCount,
            bytes = bytes,
            onRescan = onRescan,
            onDeleteConfirmed = onDeleteAllConfirmed,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
            onDialogVisibilityChange = onStorageDialogVisibilityChange
        )
    }

    pendingRemoval?.let { item ->
        RemoveDownloadDialog(
            item = item,
            onConfirm = {
                pendingRemoval = null
                onRemove(item.bookId)
            },
            onDismiss = { pendingRemoval = null }
        )
    }

    if (removeCompletedVisible) {
        RemoveCompletedDialog(
            bookCount = completedIds.size,
            onConfirm = {
                removeCompletedVisible = false
                onRemoveCompleted()
            },
            onDismiss = { removeCompletedVisible = false }
        )
    }
}

/**
 * The queue list: the «Прибрати завершені» action only exists while there IS
 * a finished download to remove (a disabled or no-op button would be a lie),
 * and every row carries its own state and controls.
 */
@Composable
private fun DownloadQueueList(
    items: List<DownloadQueueItem>,
    completedCount: Int,
    onPause: (String) -> Unit,
    onContinue: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (DownloadQueueItem) -> Unit,
    onRemoveCompleted: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("download_queue_list")) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.download_manager_queue_heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("download_queue_heading")
                        .semantics { heading() }
                )
                if (completedCount > 0) {
                    TextButton(
                        onClick = onRemoveCompleted,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("remove_completed_button")
                    ) {
                        Text(stringResource(R.string.download_manager_remove_completed))
                    }
                }
            }
        }
        items(items, key = { it.bookId }) { item ->
            DownloadQueueRow(
                item = item,
                onPause = { onPause(item.bookId) },
                onContinue = { onContinue(item.bookId) },
                onCancel = { onCancel(item.bookId) },
                onRemove = { onRemove(item) }
            )
        }
    }
}

/**
 * One queue row: the book, its chapter/size detail, its state and controls.
 * The controls follow the status's own action matrix
 * ([DownloadQueueStatus.canPause] / [DownloadQueueStatus.canContinue] /
 * [DownloadQueueStatus.canCancel]), so no row offers a button that would do
 * nothing.
 */
@Composable
private fun DownloadQueueRow(
    item: DownloadQueueItem,
    onPause: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("download_queue_item_${item.bookId}")
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.author.isNotBlank()) {
            Text(
                text = item.author,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = downloadQueueDetailText(
                downloadedChapters = item.downloadedChapters,
                totalChapters = item.totalChapters,
                bytesOnDisk = item.bytesOnDisk
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .testTag("download_queue_detail_${item.bookId}")
        )
        // A finished or failed queue has no progress to show; everything else
        // (running, waiting its turn, stopped) does.
        if (item.status != DownloadQueueStatus.DONE && item.status != DownloadQueueStatus.ERROR) {
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(4.dp)
                    .testTag("download_queue_progress_${item.bookId}"),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = downloadStatusLabel(item.status),
                style = MaterialTheme.typography.labelLarge,
                color = downloadStatusColor(item.status),
                modifier = Modifier
                    .weight(1f)
                    .testTag("download_queue_status_${item.bookId}")
            )
            if (item.status.canPause) {
                TextButton(
                    onClick = onPause,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("download_pause_${item.bookId}")
                ) {
                    Text(stringResource(R.string.download_manager_pause))
                }
            }
            if (item.status.canContinue) {
                TextButton(
                    onClick = onContinue,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("download_continue_${item.bookId}")
                ) {
                    Text(stringResource(R.string.download_manager_continue))
                }
            }
        }
        // The two stopping actions live on their own line so neither is
        // crowded: «Скасувати» keeps the files, the delete asks first.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.status.canCancel) {
                val cancelDescription = stringResource(R.string.download_manager_cancel_description)
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("download_cancel_${item.bookId}")
                        .semantics { contentDescription = cancelDescription }
                ) {
                    Text(stringResource(R.string.download_manager_cancel))
                }
            }
            TextButton(
                onClick = onRemove,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("download_remove_${item.bookId}")
            ) {
                // A finished copy has no partial files, so its single action
                // stays «Прибрати»; an unfinished one says exactly what the
                // confirmation will destroy.
                Text(
                    stringResource(
                        if (item.status.isFinished) R.string.download_manager_remove
                        else R.string.download_manager_delete_files
                    )
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The honest empty state: nothing is downloading and nothing is stored. */
@Composable
fun DownloadQueueEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .testTag("download_queue_empty")
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.download_manager_empty_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.download_manager_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The confirmation that guards destroying files. Its title and its confirm
 * button repeat the action the listener just pressed — «Прибрати» only for a
 * finished copy, «Видалити файли» for an unfinished one — so the dialog never
 * renames the action under the finger. The dismiss is «Не видаляти», not the
 * generic «Скасувати»: on this screen «Скасувати» already means «stop the
 * download and keep the files».
 */
@Composable
private fun RemoveDownloadDialog(
    item: DownloadQueueItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val finished = item.status.isFinished
    val title = stringResource(
        if (finished) R.string.download_manager_remove_title
        else R.string.download_manager_delete_title
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .testTag("remove_download_dialog")
            .accessibilityPane(title),
        title = {
            Text(
                text = title,
                modifier = Modifier
                    .testTag("remove_download_dialog_heading")
                    .semantics { heading() }
            )
        },
        text = { Text(stringResource(R.string.download_manager_remove_message, item.title)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("remove_download_confirm")
            ) {
                Text(
                    stringResource(
                        if (finished) R.string.download_manager_remove
                        else R.string.download_manager_delete_files
                    )
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("remove_download_cancel")
            ) {
                Text(stringResource(R.string.download_manager_keep))
            }
        }
    )
}

@Composable
private fun RemoveCompletedDialog(
    bookCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = stringResource(R.string.download_manager_remove_completed_title)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .testTag("remove_completed_dialog")
            .accessibilityPane(title),
        title = {
            Text(
                text = title,
                modifier = Modifier
                    .testTag("remove_completed_dialog_heading")
                    .semantics { heading() }
            )
        },
        text = { Text(removeCompletedConfirmText(bookCount)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("remove_completed_confirm")
            ) {
                Text(stringResource(R.string.download_manager_remove_completed))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("remove_completed_cancel")
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun downloadStatusLabel(status: DownloadQueueStatus): String = when (status) {
    DownloadQueueStatus.QUEUED -> stringResource(R.string.download_manager_status_queued)
    DownloadQueueStatus.DOWNLOADING -> stringResource(R.string.download_manager_status_downloading)
    DownloadQueueStatus.PAUSED -> stringResource(R.string.download_manager_status_paused)
    DownloadQueueStatus.DONE -> stringResource(R.string.download_manager_status_done)
    DownloadQueueStatus.ERROR -> stringResource(R.string.download_manager_status_error)
}

@Composable
private fun downloadStatusColor(status: DownloadQueueStatus): Color = when (status) {
    DownloadQueueStatus.DONE -> MaterialTheme.colorScheme.primary
    DownloadQueueStatus.ERROR -> MaterialTheme.colorScheme.error
    DownloadQueueStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
    DownloadQueueStatus.QUEUED, DownloadQueueStatus.PAUSED ->
        MaterialTheme.colorScheme.onSurfaceVariant
}
