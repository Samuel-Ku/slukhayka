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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import java.util.Locale

/** One curated collection as the book page shows it. */
data class CollectionWithBookRow(
    val documentId: String,
    val title: String,
    val pseudonym: String,
    /** The real average, or null when nobody voted — no stars are drawn. */
    val average: Double?,
    val ratingCount: Int
)

/**
 * Spec-51 (#692) — «Добірки з цією книгою»: the published collections that
 * contain the open book, already ranked by [com.slukhayka.audiobooks.data.collections.CollectionRanking].
 *
 * With no rows the block renders NOTHING (a book nobody curated shows no
 * empty headline), and a collection without real votes shows an honest
 * "no ratings yet" line instead of fabricated stars.
 */
@Composable
fun CollectionsWithBookBlock(
    rows: List<CollectionWithBookRow>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("collections_with_book_block")
    ) {
        Text(
            text = stringResource(R.string.collection_with_book_title),
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
                    .testTag("collection_with_book_${row.documentId}")
            ) {
                Text(text = row.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.collection_by_pseudonym, row.pseudonym),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val average = row.average
                if (average != null) {
                    val votes = pluralStringResource(R.plurals.library_rating_votes, row.ratingCount, row.ratingCount)
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
