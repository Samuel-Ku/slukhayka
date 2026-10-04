package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.ui.screens.movedOrder

/** An isolated draft: only Save submits the complete permutation. */
@Composable
fun ChapterOrderDialog(
    chapters: List<ChapterEntity>,
    saving: Boolean,
    errorMessage: String?,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var order by remember(chapters) { mutableStateOf(chapters) }
    val headingFocus = remember { FocusRequester() }
    LaunchedEffect(headingFocus) { withFrameNanos { }; headingFocus.requestFocus() }
    AlertDialog(
        modifier = Modifier.accessibilityPane(stringResource(R.string.chapter_order_edit)),
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.chapter_order_edit), modifier = Modifier.focusRequester(headingFocus).focusable().semantics { heading() }.testTag("chapter_order_heading")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                TextButton(
                    onClick = { order = order.reversed() }, enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("chapter_order_reverse")
                ) { Text(stringResource(R.string.chapter_order_reverse)) }
                order.forEachIndexed { index, chapter ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${chapter.title}", modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { order = movedOrder(order.size, index, index - 1).map { order[it] } },
                            enabled = !saving && index > 0,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("chapter_order_up_${chapter.id}")
                        ) { Text(stringResource(R.string.lib_import_move_up)) }
                        TextButton(
                            onClick = { order = movedOrder(order.size, index, index + 1).map { order[it] } },
                            enabled = !saving && index < order.lastIndex,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("chapter_order_down_${chapter.id}")
                        ) { Text(stringResource(R.string.lib_import_move_down)) }
                    }
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("chapter_order_error")) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(order.map { it.id }) },
                enabled = !saving && order.map { it.id } != chapters.map { it.id },
                modifier = Modifier.heightIn(min = 48.dp).testTag("chapter_order_save")
            ) { Text(stringResource(if (saving) R.string.chapter_order_saving else R.string.book_detail_metadata_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag("chapter_order_cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
