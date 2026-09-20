package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.PublicationPreview

/**
 * Spec-51 (#691) — the EXPLICIT confirmation before anything leaves the device.
 *
 * It shows exactly what will be published ([PublicationPreview]) and there
 * is no other way out: dismissing publishes nothing, and only «Опублікувати»
 * reaches [onConfirm]. That is the AC's "without it nothing leaves the device".
 *
 * #980 — the labels around those fields are chrome: they come from resources
 * here rather than from the pure [PublicationPreview], so the EN semantics
 * walk can actually see them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishCollectionSheet(
    preview: PublicationPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val previewLines = listOf(
        stringResource(R.string.publish_collection_preview_line_title, preview.title),
        stringResource(R.string.publish_collection_preview_line_pseudonym, preview.pseudonym),
        stringResource(R.string.publish_collection_preview_line_book_count, preview.bookCount),
        stringResource(
            if (preview.descriptionIncluded) {
                R.string.publish_collection_preview_line_description_included
            } else {
                R.string.publish_collection_preview_line_description_absent
            }
        )
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("publish_collection_sheet")
        ) {
            Text(
                text = stringResource(R.string.publish_collection_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.publish_collection_preview_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // Exactly the preview's lines — the listener sees the whole list,
            // not a summary that could hide a field.
            previewLines.forEachIndexed { index, line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .testTag("publish_collection_line_$index")
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("publish_collection_cancel")
                ) { Text(stringResource(R.string.collection_cancel)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("publish_collection_confirm")
                ) { Text(stringResource(R.string.collection_publish)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
