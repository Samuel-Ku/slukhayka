package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.ui.library.formatBytes
import com.slukhayka.audiobooks.ui.theme.AppDimens
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.theme.*

/**
 * spec-28 (#194) — the «Завантаження та пам'ять» destination's body: the
 * storage summary line, the local-folder rescan and the destructive delete,
 * which stays behind the [ClearCacheConfirmDialog] quoting the exact count
 * and size (BUG-001) — the button is named by its consequence and only
 * appears when there IS something to delete.
 *
 * #899 — the destination itself is now [DownloadManagerScreen]: the queue
 * sits above this body, and these tools are reused unchanged as its bottom
 * section (docs/specs §9 «Зберегти інструменти»). The pane owns the confirm
 * dialog's focus contract; the manager passes the memory summary line in.
 */
@Composable
fun StorageDestinationPane(
    // #885 (wave 3) — the two numbers separately, not a pre-joined line: the
    // prototype shows them as a stat PAIR (`.sl-stat-pair`, `:1218-1220`).
    occupiedBytes: Long,
    freeBytes: Long,
    // #1163 — the private copies of local imports are a THIRD figure of their
    // own, never folded into the downloads' bytes: they are the app's data but
    // not a download, and a listener who imported 3,23 ГБ must find that number
    // here instead of in Android's settings.
    localCopyBytes: Long = 0L,
    hasLocalBooks: Boolean,
    showDelete: Boolean,
    bookCount: Int,
    bytes: Long,
    onRescan: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    onDialogVisibilityChange: (Boolean) -> Unit = {}
) {
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var focusAfterDialog by remember { mutableStateOf<StorageDialogFocusTarget?>(null) }
    val deleteFocusRequester = remember { FocusRequester() }
    val storageSummaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(showClearCacheDialog) {
        if (!showClearCacheDialog) {
            when (focusAfterDialog) {
                StorageDialogFocusTarget.DeleteAction -> deleteFocusRequester.requestFocus()
                StorageDialogFocusTarget.StorageSummary -> storageSummaryFocusRequester.requestFocus()
                null -> Unit
            }
            focusAfterDialog = null
        }
    }

    StorageDestinationContent(
        occupiedBytes = occupiedBytes,
        freeBytes = freeBytes,
        localCopyBytes = localCopyBytes,
        hasLocalBooks = hasLocalBooks,
        showDelete = showDelete,
        onRescan = onRescan,
        onDeleteClick = {
            showClearCacheDialog = true
            onDialogVisibilityChange(true)
        },
        modifier = modifier.accessibilityModalBackground(showClearCacheDialog),
        deleteButtonModifier = Modifier.focusRequester(deleteFocusRequester),
        storageHeadingModifier = Modifier
            .focusRequester(storageSummaryFocusRequester)
            .focusable()
    )

    if (showClearCacheDialog) {
        ClearCacheConfirmDialog(
            bookCount = bookCount,
            bytes = bytes,
            onConfirm = {
                focusAfterDialog = StorageDialogFocusTarget.StorageSummary
                showClearCacheDialog = false
                onDialogVisibilityChange(false)
                onDeleteConfirmed()
            },
            onDismiss = {
                focusAfterDialog = StorageDialogFocusTarget.DeleteAction
                showClearCacheDialog = false
                onDialogVisibilityChange(false)
            }
        )
    }
}

/**
 * The destination body — storage info card, the local-folder rescan action
 * and the danger zone with the destructive delete. Public and stateless
 * (pure `@Composable` inputs, no ViewModel) so the snapshot seam pins both
 * the populated and the nothing-to-delete states from fixture data.
 */
@Composable
fun StorageDestinationContent(
    occupiedBytes: Long,
    freeBytes: Long,
    localCopyBytes: Long = 0L,
    hasLocalBooks: Boolean,
    showDelete: Boolean,
    onRescan: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    deleteButtonModifier: Modifier = Modifier,
    storageHeadingModifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("storage_destination_screen")
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = PaperInkIcons.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.storage_device_heading),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = storageHeadingModifier
                            .testTag("storage_device_heading")
                            .semantics { heading() }
                    )
                    // The prototype's pair: a 32 px number over a 12 px muted
                    // label, two equal columns (`:1218-1220`). #1163 gave the
                    // first stat its honest name: it is what `getAudioCacheSizeBytes()`
                    // fills — the SOURCE downloads — so it says «Завантаження»
                    // and the private local copies get their own line below
                    // rather than hiding inside this number (or outside it, as
                    // the 0 МБ complaint of #1163 did). The second is FREE
                    // SPACE, which is the pair that answers «чи стане місця» —
                    // the prototype's own second slot measures the cover cache,
                    // which the app does not track at all.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AppDimens.SpaceSm),
                        horizontalArrangement = Arrangement.spacedBy(AppDimens.SpaceLg)
                    ) {
                        StorageStat(
                            value = formatBytes(occupiedBytes),
                            label = stringResource(R.string.storage_stat_downloads),
                            modifier = Modifier.weight(1f)
                        )
                        StorageStat(
                            value = formatBytes(freeBytes),
                            label = stringResource(R.string.storage_stat_free),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // #1163 — the third figure, and only when there IS one: a
                    // listener whose library is local imports only must see the
                    // bytes the app holds for them, named «Локальні копії» and
                    // never called a download. Zero is not drawn — a permanent
                    // «Локальні копії — 0 МБ» row would be noise for the
                    // majority who never imported a folder.
                    if (localCopyBytes > 0L) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = AppDimens.SpaceSm)
                                .testTag("storage_local_copies_row"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.storage_stat_local_copies),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = formatBytes(localCopyBytes),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("storage_local_copies_value")
                            )
                        }
                    }
                }
            }
        }

        if (hasLocalBooks) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("rescan_folders_button")
            ) {
                Icon(imageVector = PaperInkIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.storage_rescan_action))
            }
        }

        if (showDelete) {
            // The destructive action gets its own section, separated from the
            // neutral storage info (ADR-0014: a destructive action never sits
            // next to neutral data) and styled destructive (danger colour).
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.storage_danger_heading),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.storage_delete_consequence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onDeleteClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                shape = RoundedCornerShape(AppDimens.RadiusCardLg),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("clear_cache_button")
                    .then(deleteButtonModifier)
            ) {
                Icon(imageVector = PaperInkIcons.Trash, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.storage_delete_action))
            }
        }
    }
}

private enum class StorageDialogFocusTarget {
    DeleteAction,
    StorageSummary
}

/**
 * #885 (wave 3) — one column of the storage stat pair, from the prototype's
 * `.sl-stat-pair`: the number at 32 px with tight tracking, the label 12 px and
 * muted. Two of these side by side are the README the listener needs — how
 * much is here, how much is left.
 */
@Composable
private fun StorageStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
