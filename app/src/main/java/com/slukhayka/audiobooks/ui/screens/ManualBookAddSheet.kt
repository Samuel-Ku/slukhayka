package com.slukhayka.audiobooks.ui.screens

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.slukhayka.audiobooks.data.bibliography.BibliographyCandidate
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.entries.ManualBookAddRequest
import com.slukhayka.audiobooks.data.entries.ReadingFormat
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * ADR-0046 §§3–4 / spec-54 T15 (#870) — adding a book of ANY format by hand,
 * plus ADR-0053 / #854 — the tracked Work: a title and an author whose audio
 * no source carries yet.
 *
 * The sheet collects ONLY what the listener really knows: a title, an author,
 * the format, and two explicit choices. It cannot invent a "want to read" and
 * it never names an Edition — that is the policy's job, and the policy refuses
 * anything fiction-like. The tracked mode carries no format at all: it writes
 * no Edition and no Source, only the honest «аудіо недоступне» card.
 *
 * #855 (T2) gives the tracked mode one more thing the listener may know: a
 * cover URL. It is the SAME door the cover already travels (the ordinary
 * cover write path) — and leaving it blank is a real answer: the card shows
 * the absence, never a placeholder image.
 */
@Composable
fun ManualBookAddSheet(
    onAdd: (ManualBookAddRequest) -> Unit,
    onDismiss: () -> Unit,
    /** ADR-0053 / #854 — title + author of a tracked Work (no audio yet). */
    onAddTracked: (title: String, author: String, coverUrl: String?) -> Unit,
    /**
     * #857 (T4) — the external-bibliography door: given a query, the provider
     * answers candidates (title, authors, year, cover). Null hides the search
     * row entirely, so every existing host and test keeps today's sheet
     * byte-for-byte.
     */
    onSearchBibliography: (suspend (String) -> List<BibliographyCandidate>)? = null,
    now: Long = System.currentTimeMillis()
) {
    var title by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf(ReadingFormat.PAPER.name) }
    var tracked by rememberSaveable { mutableStateOf(false) }
    var coverUrl by rememberSaveable { mutableStateOf("") }
    var wantsToRead by rememberSaveable { mutableStateOf(false) }
    var importedOwnFile by rememberSaveable { mutableStateOf(false) }
    // #857 — the corner's search: a query, its candidates and the honest
    // "partly answered / nothing found" state.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<BibliographyCandidate>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val canAdd = title.isNotBlank() && author.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manual_add_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // #857 (T4) — the search door sits ABOVE the fields: its whole
                // point is that the listener need not type metadata by hand.
                // Selecting a candidate FILLS the fields rather than replacing
                // the form, so a wrong pick stays correctable.
                if (onSearchBibliography != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text(stringResource(R.string.manual_add_search_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("manual_add_search_query")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            enabled = searchQuery.isNotBlank() && !searching,
                            onClick = {
                                searching = true
                                scope.launch {
                                    candidates = runCatching {
                                        onSearchBibliography(searchQuery)
                                    }.getOrDefault(emptyList())
                                    searched = true
                                    searching = false
                                }
                            },
                            modifier = Modifier.testTag("manual_add_search_go")
                        ) { Text(stringResource(R.string.manual_add_search)) }
                    }
                    if (searched && candidates.isEmpty()) {
                        Text(
                            text = stringResource(R.string.manual_add_search_empty),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("manual_add_search_empty")
                        )
                    }
                    candidates.forEach { candidate ->
                        OutlinedButton(
                            onClick = {
                                title = candidate.title
                                candidate.authors.firstOrNull()?.let { author = it }
                                candidate.coverImageUrl?.let { coverUrl = it }
                                tracked = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_add_candidate_${candidate.workKey ?: candidate.title}")
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(candidate.title)
                                val meta = listOfNotNull(
                                    candidate.authors.firstOrNull(),
                                    candidate.firstPublishYear?.toString()
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(meta, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.manual_add_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_add_name")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.manual_add_author)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_add_author")
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.manual_add_format),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ReadingFormat.PAPER to R.string.manual_add_format_paper,
                        ReadingFormat.EBOOK to R.string.manual_add_format_ebook
                    ).forEach { (value, labelRes) ->
                        FilterChip(
                            selected = !tracked && format == value.name,
                            onClick = {
                                format = value.name
                                tracked = false
                            },
                            label = { Text(stringResource(labelRes)) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("manual_add_format_${value.name.lowercase()}")
                        )
                    }
                    FilterChip(
                        selected = tracked,
                        onClick = { tracked = true },
                        label = { Text(stringResource(R.string.manual_add_format_tracked)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("manual_add_format_tracked")
                    )
                }
                if (tracked) {
                    // #855 (T2) — the cover the listener already knows, if any.
                    // Blank stays blank: an honest absence, not a placeholder.
                    OutlinedTextField(
                        value = coverUrl,
                        onValueChange = { coverUrl = it },
                        label = { Text(stringResource(R.string.manual_add_cover)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_add_cover")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // ADR-0053 — the honest tracked mode: no format choices,
                    // because there is no audio to describe yet.
                    Text(
                        text = stringResource(R.string.manual_add_tracked_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ChoiceRow(
                        checked = wantsToRead,
                        onCheckedChange = { wantsToRead = it },
                        label = stringResource(R.string.manual_add_wants_to_read),
                        tag = "manual_add_wants"
                    )
                    ChoiceRow(
                        checked = importedOwnFile,
                        onCheckedChange = { importedOwnFile = it },
                        label = stringResource(R.string.manual_add_own_file),
                        tag = "manual_add_own_file"
                    )
                    Text(
                        text = stringResource(R.string.manual_add_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (tracked) {
                        onAddTracked(
                            title.trim(),
                            author.trim(),
                            coverUrl.trim().ifEmpty { null }
                        )
                    } else {
                        onAdd(
                            ManualBookAddRequest(
                                title = title,
                                author = author,
                                format = ReadingFormat.valueOf(format),
                                importedOwnFile = importedOwnFile,
                                wantsToRead = wantsToRead,
                                now = now
                            )
                        )
                    }
                },
                enabled = canAdd,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("manual_add_confirm")
            ) {
                Text(stringResource(R.string.manual_add_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("manual_add_cancel")
            ) {
                Text(stringResource(R.string.manual_add_cancel))
            }
        }
    )
}

@Composable
private fun ChoiceRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .heightIn(min = AppDimens.TouchTarget)
                .testTag(tag)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
