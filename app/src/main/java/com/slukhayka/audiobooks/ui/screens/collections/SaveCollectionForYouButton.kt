package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Spec-51 (#695) — «Зберегти собі» on someone else's visible collection.
 *
 * The action is only offered when the original is actually here to copy
 * (`ForkPolicy` forks a LOCAL collection). When it is not, the control is
 * DISABLED rather than hidden: the listener sees that saving exists and is
 * unavailable right now, instead of a button that silently does nothing.
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
        Text("Зберегти собі")
    }
}
