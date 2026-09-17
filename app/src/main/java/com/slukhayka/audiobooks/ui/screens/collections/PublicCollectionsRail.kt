package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** The real average line, or the honest absence — one rule for every surface. */
@Composable
internal fun CollectionRatingLine(
    average: Double?,
    ratingCount: Int,
    modifier: Modifier = Modifier
) {
    if (average != null) {
        val votes = pluralStringResource(R.plurals.library_rating_votes, ratingCount, ratingCount)
        Text(
            text = String.format(Locale.US, "★ %.1f · %s", average, votes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    } else {
        Text(
            text = stringResource(R.string.collection_no_ratings),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    }
}

/**
 * Spec-51 (#693) — «Добірки слухачів» on the Listen screen: the top public
 * collections by the SAME [com.slukhayka.audiobooks.data.collections.CollectionRanking]
 * the book-page block uses. Hidden collections never reach this list (the read
 * path filters them), and no rows means no rail at all.
 */
@Composable
fun PublicCollectionsRail(
    rows: List<CollectionWithBookRow>,
    onOpen: (String) -> Unit,
    onOpenCurator: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("public_collections_rail")
    ) {
        Text(
            text = stringResource(R.string.collections_rail_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(rows, key = { it.documentId }) { row ->
                Column(
                    modifier = Modifier
                        .width(200.dp)
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onOpen(row.documentId) }
                        .testTag("rail_collection_${row.documentId}")
                ) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2
                    )
                    TextButton(
                        onClick = { onOpenCurator(row.authorId) },
                        modifier = Modifier.testTag("rail_curator_${row.documentId}")
                    ) {
                        Text(stringResource(R.string.collection_by_pseudonym, row.pseudonym))
                    }
                    CollectionRatingLine(average = row.average, ratingCount = row.ratingCount)
                }
            }
        }
    }
}

/**
 * Spec-51 (#693) — a curator's profile: the pseudonym as a heading and that
 * curator's VISIBLE collections. With nothing visible the profile says so
 * honestly instead of rendering an empty shelf.
 */
@Composable
fun CuratorProfileContent(
    pseudonym: String,
    rows: List<CollectionWithBookRow>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("curator_profile")
    ) {
        Text(
            text = pseudonym,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
                .testTag("curator_profile_pseudonym")
        )
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.curator_profile_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("curator_profile_empty")
            )
            return@Column
        }
        rows.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onOpen(row.documentId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("curator_collection_${row.documentId}")
            ) {
                Text(text = row.title, style = MaterialTheme.typography.bodyLarge)
                CollectionRatingLine(average = row.average, ratingCount = row.ratingCount)
            }
        }
    }
}
