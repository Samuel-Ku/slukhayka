package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One published collection as the Library shows it. */
data class PublishedCollectionRow(
    val documentId: String,
    val title: String,
    val bookCount: Int,
    val pseudonym: String
)

/**
 * Spec-51 (#691) — the listener's OWN published collections, read back from the
 * shared store. This surface exists only when a shared store is configured
 * (the caller gates on `publicCollectionsAvailable`), so an empty list here
 * means "nothing published yet", not "publishing is unavailable".
 *
 * With no rows the block renders NOTHING: the local «Мої добірки» block already
 * carries the honest empty state, and a second empty headline would be noise.
 */
@Composable
fun PublishedCollectionsBlock(
    rows: List<PublishedCollectionRow>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("published_collections_block")
    ) {
        Text(
            text = "Опубліковані добірки",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))
        rows.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onOpen(row.documentId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("published_collection_row_${row.documentId}")
            ) {
                Text(text = row.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${row.bookCount} книг · ${row.pseudonym}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
