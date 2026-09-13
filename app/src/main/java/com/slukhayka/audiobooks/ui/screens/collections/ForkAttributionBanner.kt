package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.collections.ForkAttribution

/**
 * Spec-51 (#695) — a fork's attribution.
 *
 * The TEXT is a frozen snapshot: it is ALWAYS shown, and changes to the
 * original (rename, deletion) never rewrite it. Only the LINK is conditional —
 * it exists while the original is visible, and afterwards the same text simply
 * stops being clickable. That asymmetry is the whole point of this surface.
 */
@Composable
fun ForkAttributionBanner(
    attribution: ForkAttribution,
    originalVisible: Boolean,
    onOpenOriginal: (documentId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val linkable = attribution.linkAvailable(originalVisible)
    Text(
        text = attribution.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .then(
                if (linkable) {
                    Modifier.clickable { onOpenOriginal(attribution.sourceDocumentId) }
                } else {
                    Modifier
                }
            )
            .testTag(if (linkable) "fork_attribution_link" else "fork_attribution_plain")
    )
}
