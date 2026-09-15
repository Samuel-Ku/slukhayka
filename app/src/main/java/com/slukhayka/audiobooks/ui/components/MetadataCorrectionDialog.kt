package com.slukhayka.audiobooks.ui.components

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R

/**
 * Spec-53 T7/T9 — the ONE correction form for the three claims a parse gets
 * wrong, shared by the post-import fix (book page) and the pre-add preview
 * (submission sheet). One honest rule: the title cannot be saved blank (the
 * card would lose its name), while a cleared author or narrator really
 * clears the claim.
 */
@Composable
fun MetadataCorrectionDialog(
    initialTitle: String,
    initialAuthor: String,
    initialNarrator: String,
    onDismiss: () -> Unit,
    onSave: (title: String, author: String, narrator: String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var author by remember { mutableStateOf(initialAuthor) }
    var narrator by remember { mutableStateOf(initialNarrator) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_detail_correct_metadata)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.book_detail_metadata_title)) },
                    singleLine = true,
                    isError = title.isBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("metadata_edit_title")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.book_detail_metadata_author)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("metadata_edit_author")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = narrator,
                    onValueChange = { narrator = it },
                    label = { Text(stringResource(R.string.book_detail_metadata_narrator)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("metadata_edit_narrator")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, author, narrator) },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag("metadata_edit_save")
            ) {
                Text(stringResource(R.string.book_detail_metadata_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.book_detail_cancel))
            }
        },
        modifier = Modifier.testTag("metadata_correction_dialog")
    )
}
