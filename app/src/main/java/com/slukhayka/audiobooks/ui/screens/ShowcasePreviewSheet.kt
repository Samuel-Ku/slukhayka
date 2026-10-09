package com.slukhayka.audiobooks.ui.screens

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
import com.slukhayka.audiobooks.data.achievements.ShowcasePreview

/**
 * #705 (T7) — the EXPLICIT confirmation before the showcase goes public.
 *
 * Modelled on [com.slukhayka.audiobooks.ui.screens.collections.PublishCollectionSheet],
 * which set this shape for collections in spec-51 (#691): the listener sees
 * exactly what travels, dismissing sends nothing, and only «Опублікувати»
 * reaches [onConfirm].
 *
 * It lists the awards BY NAME rather than as a count. «До 3 нагород» is not
 * something a person can consent to meaningfully: the choice being made is
 * WHICH awards become public, so the sheet shows the names themselves.
 *
 * The preview is never empty here — [ShowcasePreviewFactory] returns null
 * instead of an empty confirmation, and the caller does not open this sheet for
 * one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowcasePreviewSheet(
    preview: ShowcasePreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("showcase_publish_sheet")
        ) {
            Text(
                text = stringResource(R.string.showcase_publish_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.showcase_publish_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(
                    R.string.showcase_publish_line_pseudonym,
                    preview.pseudonym
                ),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("showcase_publish_line_0")
            )
            Text(
                text = stringResource(
                    R.string.showcase_publish_line_award_count,
                    preview.awardCount
                ),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("showcase_publish_line_1")
            )
            Spacer(Modifier.height(4.dp))
            preview.awards.forEachIndexed { index, award ->
                Text(
                    text = award.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .testTag("showcase_publish_award_$index")
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("showcase_publish_cancel")
                ) { Text(stringResource(R.string.collection_cancel)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("showcase_publish_confirm")
                ) { Text(stringResource(R.string.showcase_publish_confirm)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
