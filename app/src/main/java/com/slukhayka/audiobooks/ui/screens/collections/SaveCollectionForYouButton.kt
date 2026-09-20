package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R

/**
 * Spec-51 (#695) — «Зберегти собі» on someone else's visible collection.
 *
 * The action is only offered when the original is actually here to copy
 * (`ForkPolicy` forks a LOCAL collection). When it is not, the control is
 * DISABLED rather than hidden: the listener sees that saving exists and is
 * unavailable right now, instead of a button that silently does nothing.
 *
 * #980 — the label is chrome, so it comes from a resource: as a literal it
 * was one of the strings the EN walk could not afford to look at.
 */
@Composable
fun SaveCollectionForYouButton(
    originalAvailableLocally: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onSave,
        enabled = originalAvailableLocally,
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag("save_collection_for_you")
    ) {
        Text(stringResource(R.string.collection_save_for_you))
    }
}
