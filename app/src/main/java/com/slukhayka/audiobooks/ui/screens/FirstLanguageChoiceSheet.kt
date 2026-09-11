package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.accessibilityPane

/**
 * Spec-51 (#742) T2 — the one-time First Language Choice, the app's first-run
 * question: «якими мовами хочеш книжки?». It opens once, after the first
 * catalogue sync that actually wrote renditions, and offers exactly the
 * languages that have content — all of them on by default — with the quick
 * action «Лише українські» and «Готово» for an explicit selection.
 *
 * ANY answer is terminal (the engine persists it): dismissing the sheet keeps
 * the default «Усі», so there is no neutral escape that would ask again.
 * It replaces the spec-45 bilingual prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstLanguageChoiceSheet(
    languages: List<String>,
    onUkrainianOnly: () -> Unit,
    onDone: (Set<String>) -> Unit
) {
    ModalBottomSheet(
        // Dismissing is an answer too: keep the default «Усі».
        onDismissRequest = { onDone(emptySet()) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Decorative handle: no extra TalkBack stop (#371 pattern).
        dragHandle = { BottomSheetDefaults.DragHandle(modifier = Modifier.clearAndSetSemantics {}) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.accessibilityPane(stringResource(R.string.content_languages_title))
    ) {
        FirstLanguageChoiceContent(
            languages = languages,
            onUkrainianOnly = onUkrainianOnly,
            onDone = onDone
        )
    }
}

/**
 * The sheet body, extracted so the harness pins it without hosting a
 * `ModalBottomSheet` window (the LibraryFilterSheet pattern).
 */
@Composable
fun FirstLanguageChoiceContent(
    languages: List<String>,
    onUkrainianOnly: () -> Unit,
    onDone: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember(languages) { mutableStateOf(languages.toSet()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("first_language_choice")
    ) {
        Text(
            text = stringResource(R.string.first_language_choice_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.first_language_choice_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        languages.forEach { tag ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selected = if (tag in selected) selected - tag else selected + tag
                    }
                    .padding(vertical = 4.dp)
                    .testTag("first_language_choice_$tag")
            ) {
                Checkbox(
                    checked = tag in selected,
                    onCheckedChange = { checked ->
                        selected = if (checked) selected + tag else selected - tag
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(text = contentLanguageLabel(tag), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onUkrainianOnly) {
                Text(stringResource(R.string.first_language_choice_ukrainian_only))
            }
            Spacer(Modifier.width(8.dp))
            // «Усі» as an explicit quick action: keeps every language (the
            // empty selection), never a neutral escape.
            TextButton(onClick = { onDone(emptySet()) }) {
                Text(stringResource(R.string.first_language_choice_all))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    // Everything on IS «Усі» (the empty selection), so a later
                    // language with content appears without another question.
                    onDone(if (selected.size == languages.size) emptySet() else selected)
                }
            ) {
                Text(stringResource(R.string.first_language_choice_done))
            }
        }
    }
}
