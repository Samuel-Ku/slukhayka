package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.ListenerCollection
import com.slukhayka.audiobooks.ui.components.accessibilityPane

/**
 * Spec-51 (#690) — one own collection: its composition in INSERTION order with
 * each book's reason, plus editing and a delete that asks first.
 *
 * Deleting is destructive, so the confirmation is part of this seam: cancelling
 * must destroy nothing (it only closes the dialog), and only the explicit
 * confirm reaches [onDelete].
 */
@Composable
fun CollectionDetailContent(
    collection: ListenerCollection,
    onRemoveBook: (bookId: String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // A real pane contract: a screen reader announces the collection
            // as a pane named after it, not as a loose pile of nodes.
            .accessibilityPane(collection.title)
            .testTag("collection_detail")
    ) {
        Text(
            text = collection.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
                .testTag("collection_title")
        )
        if (collection.description.isNotBlank()) {
            Text(
                text = collection.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("collection_description")
            )
        }
        Spacer(Modifier.height(12.dp))

        if (collection.items.isEmpty()) {
            Text(
                text = stringResource(R.string.collection_detail_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("collection_empty")
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(collection.items, key = { it.bookId }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("collection_item_${item.bookId}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(text = item.bookId, style = MaterialTheme.typography.bodyLarge)
                            if (item.reason.isNotBlank()) {
                                Text(
                                    text = item.reason,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { onRemoveBook(item.bookId) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("collection_item_remove_${item.bookId}")
                        ) { Text(stringResource(R.string.collection_remove_book)) }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = { confirmingDelete = true },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .heightIn(min = 48.dp)
                .testTag("collection_delete")
        ) { Text(stringResource(R.string.collection_delete)) }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.collection_delete_title)) },
            text = { Text(stringResource(R.string.collection_delete_body, collection.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("collection_delete_confirm")
                ) { Text(stringResource(R.string.collection_delete_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingDelete = false },
                    modifier = Modifier.testTag("collection_delete_cancel")
                ) { Text(stringResource(R.string.collection_cancel)) }
            }
        )
    }
}
