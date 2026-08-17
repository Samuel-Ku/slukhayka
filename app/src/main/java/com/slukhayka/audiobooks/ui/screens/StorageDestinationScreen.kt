package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.library.ukPlural
import com.slukhayka.audiobooks.ui.theme.*

/**
 * spec-28 (#194) — the «Завантаження та пам'ять» destination: the storage
 * line and the destructive delete, moved off the main Медіатека screen into
 * a pushed screen reached from the ⋮ overflow menu. Deleting every download
 * stays behind the [ClearCacheConfirmDialog] quoting the exact count and
 * size (BUG-001) — the button is named by its consequence and only appears
 * when there IS something to delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageDestinationScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit
) {
    val libraryBooks by viewModel.libraryBooks.collectAsState()
    val cacheSizeFormatted by viewModel.cacheSizeFormatted.collectAsState()
    // The raw bytes back the confirm dialog's exact scope, and gate the
    // delete button (nothing to delete → no button).
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsState()
    var showClearCacheDialog by remember { mutableStateOf(false) }

    val offlineCount = libraryBooks.count { it.book.isDownloaded }
    val hasLocalBooks = libraryBooks.any { it.isLocal }

    Scaffold(
        topBar = {
            // Host Scaffold in MainActivity already consumed the status bar
            // (innerPadding.top); don't let this inner TopAppBar add it again.
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = "Завантаження та пам'ять",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        StorageDestinationContent(
            storageText = "$cacheSizeFormatted · $offlineCount " +
                ukPlural(offlineCount, "аудіокнига", "аудіокниги", "аудіокниг") + " offline",
            hasLocalBooks = hasLocalBooks,
            showDelete = offlineCount > 0 || cacheSizeBytes > 0L,
            onRescan = { viewModel.rescanLocalFolders() },
            onDeleteClick = { showClearCacheDialog = true },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }

    if (showClearCacheDialog) {
        ClearCacheConfirmDialog(
            bookCount = offlineCount,
            bytes = cacheSizeBytes,
            onConfirm = {
                showClearCacheDialog = false
                viewModel.clearAllAudioCache()
            },
            onDismiss = { showClearCacheDialog = false }
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
    storageText: String,
    hasLocalBooks: Boolean,
    showDelete: Boolean,
    onRescan: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
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
                    imageVector = Icons.Default.SdCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Пам'ять пристрою",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = storageText,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (hasLocalBooks) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier.testTag("rescan_folders_button")
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Пересканувати локальні файли")
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
                text = "Небезпечна зона",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Видаляє всі завантажені файли з пристрою. Цю дію не можна скасувати.",
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
                    .height(48.dp)
                    .testTag("clear_cache_button")
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Видалити завантажені файли")
            }
        }
    }
}
