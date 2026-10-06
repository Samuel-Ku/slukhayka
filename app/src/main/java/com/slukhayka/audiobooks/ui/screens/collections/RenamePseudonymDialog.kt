package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.PublishedCollectionCodec

/**
 * Spec-51 (#691) — changing the public name after publishing.
 *
 * The pseudonym is one name for ALL of a curator's collections (the store
 * replicates it, and `renameAuthor` is a partial write precisely so the
 * collections survive it — #1150). So this is not "rename a collection": it is
 * "rename yourself", and the body says so, because a listener who expected one
 * collection to change would be surprised by all of them changing.
 *
 * The field starts at the CURRENT name: renaming is an edit, not a fresh entry,
 * and an empty box would make the listener retype what they already have.
 */
@Composable
fun RenamePseudonymDialog(
    current: String,
    onConfirm: (pseudonym: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by rememberSaveable(current) { mutableStateOf(current) }
    val clean = draft.trim().take(PublishedCollectionCodec.MAX_PSEUDONYM_LEN)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("rename_pseudonym_dialog"),
        title = { Text(stringResource(R.string.rename_pseudonym_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.rename_pseudonym_body),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft,
                    // Bounded as it is typed, like the publish sheet: the limit
                    // is something the listener meets, not a silent truncation.
                    onValueChange = {
                        draft = it.take(PublishedCollectionCodec.MAX_PSEUDONYM_LEN)
                    },
                    label = { Text(stringResource(R.string.publish_collection_pseudonym_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_pseudonym_field")
                )
            }
        },
        confirmButton = {
            TextButton(
                // A blank name is not a name: the store would refuse it, and the
                // dialog says so by staying disabled rather than by failing.
                enabled = clean.isNotEmpty(),
                onClick = { onConfirm(clean) },
                modifier = Modifier.testTag("rename_pseudonym_confirm")
            ) { Text(stringResource(R.string.rename_pseudonym_confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("rename_pseudonym_cancel")
            ) { Text(stringResource(R.string.collection_cancel)) }
        }
    )
}
