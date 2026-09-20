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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import java.util.Locale

/** One published collection as the Library shows it. */
data class PublishedCollectionRow(
    val documentId: String,
    val title: String,
    val bookCount: Int,
    val pseudonym: String,
    /** #694 — the real average, or null when nobody voted (no stars drawn). */
    val average: Double? = null,
    val ratingCount: Int = 0,
    /** #696 — hidden by the community: the author sees the state, nothing else. */
    val hidden: Boolean = false
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
            text = stringResource(R.string.published_collections_title),
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
                    text = pluralStringResource(R.plurals.book_count, row.bookCount, row.bookCount) +
                        " · " + row.pseudonym,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (row.hidden) {
                    Text(
                        text = stringResource(R.string.collection_hidden_by_reports),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                // #694 — the rating summary travels with every collection card.
                val average = row.average
                if (average != null) {
                    val votes = pluralStringResource(
                        R.plurals.library_rating_votes,
                        row.ratingCount,
                        row.ratingCount
                    )
                    Text(
                        text = String.format(Locale.US, "★ %.1f · %s", average, votes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.collection_no_ratings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
