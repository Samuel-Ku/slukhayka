package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.theme.AppDimens
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import kotlinx.coroutines.launch

/**
 * spec-28 (#194) — the «+ Додати» sheet: one import action opening a sheet
 * with the two source options (files / folder) instead of two competing
 * buttons in the header. Navigation depth per ADR-0018: the add-audio source
 * picker is a *sheet*, not a pushed screen. The sheet only reports the
 * choice — the SAF launchers stay with [LibraryScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryImportSheet(
    onImportFile: () -> Unit,
    onImportFolder: () -> Unit,
    onDismiss: () -> Unit,
    returnFocusRequester: FocusRequester? = null
) {
    val scope = rememberCoroutineScope()
    val headingFocusRequester = remember { FocusRequester() }
    fun finishAndRestore(action: () -> Unit) {
        action()
        returnFocusRequester?.let { requester ->
            scope.launch {
                withFrameNanos { }
                requester.requestFocus()
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { finishAndRestore(onDismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.accessibilityPane(stringResource(R.string.a11y_library_import_pane))
    ) {
        LibraryImportSheetContent(
            onImportFile = { finishAndRestore(onImportFile) },
            onImportFolder = { finishAndRestore(onImportFolder) },
            headingFocusRequester = headingFocusRequester,
            includePaneSemantics = false,
            onClose = { finishAndRestore(onDismiss) }
        )
    }
}

/**
 * The sheet body, extracted so the snapshot seam pins the options without
 * hosting a `ModalBottomSheet` window.
 */
@Composable
fun LibraryImportSheetContent(
    onImportFile: () -> Unit,
    onImportFolder: () -> Unit,
    headingFocusRequester: FocusRequester? = null,
    includePaneSemantics: Boolean = true,
    onClose: (() -> Unit)? = null
) {
    val localHeadingFocusRequester = remember { FocusRequester() }
    val effectiveHeadingFocusRequester = headingFocusRequester ?: localHeadingFocusRequester
    LaunchedEffect(effectiveHeadingFocusRequester) {
        withFrameNanos { }
        effectiveHeadingFocusRequester.requestFocus()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (includePaneSemantics) {
                    Modifier.accessibilityPane(stringResource(R.string.a11y_library_import_pane))
                } else Modifier
            )
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .testTag("library_import_sheet_content")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Додати аудіо",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(effectiveHeadingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("library_import_sheet_heading")
            )
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.a11y_library_import_close))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        ImportOptionRow(
            icon = Icons.Default.FileUpload,
            title = "Додати файл",
            subtitle = "Одна аудіокнига з пристрою",
            tag = "import_option_file",
            onClick = onImportFile
        )
        Spacer(modifier = Modifier.height(8.dp))
        ImportOptionRow(
            icon = Icons.Default.CreateNewFolder,
            title = "Додати папку",
            subtitle = "Усі аудіофайли у вибраній папці",
            tag = "import_option_folder",
            onClick = onImportFolder
        )
    }
}

@Composable
private fun ImportOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
            .clickable(onClick = onClick)
            .testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
