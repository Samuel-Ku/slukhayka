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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.ListenerCollection
import com.slukhayka.audiobooks.data.collections.ListenerCollectionLimits
import com.slukhayka.audiobooks.data.collections.PublicationPreviewFactory
import com.slukhayka.audiobooks.data.collections.PublishedCollectionCodec

/**
 * Spec-51 (#691) — the EXPLICIT confirmation before anything leaves the device.
 *
 * It shows exactly what will be published and there is no other way out:
 * dismissing publishes nothing, and only «Опублікувати» reaches [onConfirm].
 * That is the AC's "without it nothing leaves the device".
 *
 * #1154 — the pseudonym is entered HERE, and that is a correction rather than a
 * convenience. `PublicationPreviewFactory.of` has always required a pseudonym
 * and the sheet has always displayed one, but nothing in the app ever asked for
 * it: the caller was supposed to supply it out of nowhere, so the whole publish
 * path was unreachable. The field makes the sheet self-sufficient — the preview
 * is derived live from what the listener is typing, which is also the honest
 * order: you see what goes out as you decide it, not after.
 *
 * #980 — the labels around those fields are chrome: they come from resources
 * here rather than from the pure preview model, so the EN semantics walk can
 * actually see them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishCollectionSheet(
    collection: ListenerCollection,
    onConfirm: (pseudonym: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pseudonym by rememberSaveable { mutableStateOf("") }

    // Why this collection can never be published, whatever the pseudonym says.
    // Kept apart from the "type a name" state on purpose: one is a fact about
    // the collection, the other is something the listener can fix right here.
    val impossibleRes = when {
        !ListenerCollectionLimits.isWritableTitle(collection.title) ->
            R.string.publish_collection_impossible_title
        collection.items.isEmpty() -> R.string.publish_collection_impossible_empty
        else -> null
    }
    val preview = remember(collection, pseudonym) {
        PublicationPreviewFactory.of(collection, pseudonym)
    }

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

            OutlinedTextField(
                value = pseudonym,
                // Bounded as it is typed, so the limit is something the listener
                // meets rather than something that silently truncates later.
                onValueChange = { pseudonym = it.take(PublishedCollectionCodec.MAX_PSEUDONYM_LEN) },
                label = { Text(stringResource(R.string.publish_collection_pseudonym_label)) },
                supportingText = {
                    Text(stringResource(R.string.publish_collection_pseudonym_hint))
                },
                singleLine = true,
                enabled = impossibleRes == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("publish_collection_pseudonym")
            )
            Spacer(Modifier.height(12.dp))

            when {
                // Nothing can be done here, so nothing is offered.
                impossibleRes != null -> Text(
                    text = stringResource(impossibleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("publish_collection_impossible")
                )

                // Fixable right here, and the confirm button stays disabled
                // until it is: an empty confirmation is not a confirmation.
                preview == null -> Text(
                    text = stringResource(R.string.publish_collection_pseudonym_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("publish_collection_awaiting_pseudonym")
                )

                // Exactly the preview's lines — the listener sees the whole
                // list, not a summary that could hide a field.
                else -> listOf(
                    stringResource(R.string.publish_collection_preview_line_title, preview.title),
                    stringResource(
                        R.string.publish_collection_preview_line_pseudonym,
                        preview.pseudonym
                    ),
                    stringResource(
                        R.string.publish_collection_preview_line_book_count,
                        preview.bookCount
                    ),
                    stringResource(
                        if (preview.descriptionIncluded) {
                            R.string.publish_collection_preview_line_description_included
                        } else {
                            R.string.publish_collection_preview_line_description_absent
                        }
                    )
                ).forEachIndexed { index, line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .testTag("publish_collection_line_$index")
                    )
                }
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
                    onClick = { preview?.let { onConfirm(it.pseudonym) } },
                    enabled = preview != null,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("publish_collection_confirm")
                ) { Text(stringResource(R.string.collection_publish)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
