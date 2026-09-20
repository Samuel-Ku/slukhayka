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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.ListenerCollection
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.data.collections.ListenerCollectionLimits

/**
 * Spec-51 (#689) — «Додати до добірки» on the book page. The sheet lists the
 * listener's OWN collections with a tick when this book is already in one, and
 * lets a new one be created inline — without leaving the page. Everything is
 * local, so the sheet works offline.
 *
 * @param collections the listener's collections, from the local store.
 * @param bookId the book being added.
 * @param onToggle (collectionId, add, reason) — remove when [add] is false.
 * @param onCreate (title, description) — the store applies the hygiene.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCollectionSheet(
    collections: List<ListenerCollection>,
    bookId: String,
    onDismiss: () -> Unit,
    onToggle: (collectionId: String, add: Boolean, reason: String?) -> Unit,
    onCreate: (title: String, description: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var creating by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .accessibilityPane(stringResource(R.string.book_detail_add_to_collection))
                .testTag("add_to_collection_sheet")
        ) {
            Text(
                text = stringResource(R.string.book_detail_add_to_collection),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(collections, key = { it.id }) { collection ->
                    val alreadyThere = collection.contains(bookId)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("collection_row_${collection.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = alreadyThere,
                            onCheckedChange = { checked -> onToggle(collection.id, checked, null) }
                        )
                        Text(
                            text = collection.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            if (creating) {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.collection_new_title_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.collection_new_title_limit,
                                ListenerCollectionLimits.MAX_TITLE_LEN
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_collection_title")
                )
                OutlinedTextField(
                    value = newDescription,
                    onValueChange = { newDescription = it },
                    label = { Text(stringResource(R.string.collection_new_description_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_collection_description")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { creating = false }) {
                        Text(stringResource(R.string.collection_cancel))
                    }
                    Button(
                        onClick = {
                            onCreate(newTitle, newDescription.ifBlank { null })
                            creating = false
                            newTitle = ""
                            newDescription = ""
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("new_collection_confirm")
                    ) { Text(stringResource(R.string.collection_create)) }
                }
            } else {
                TextButton(
                    onClick = { creating = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("new_collection_open")
                ) { Text(stringResource(R.string.collection_new_open)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
