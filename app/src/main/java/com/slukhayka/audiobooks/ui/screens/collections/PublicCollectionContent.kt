package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.CollectionRating
import com.slukhayka.audiobooks.data.collections.PublishedCollection
import com.slukhayka.audiobooks.ui.screens.ReviewStarsRow
import java.util.Locale

/**
 * Spec-51 (#692/#694) — someone else's VISIBLE collection, as a listener reads
 * it.
 *
 * It is deliberately read-only: the composition is shown with its curator's
 * pseudonym (never a uid), and the only actions are the rating stars — hidden
 * on the author's OWN collection, because an author never rates themselves —
 * and «Зберегти собі» (#695), which creates the reader's OWN local copy.
 * Editing belongs to the owner.
 */
@Composable
fun PublicCollectionContent(
    collection: PublishedCollection,
    originalAvailableLocally: Boolean,
    onSaveForYou: () -> Unit,
    modifier: Modifier = Modifier,
    /** The listener's own stars, or null when they have not voted. */
    myStars: Int? = null,
    /** The viewer IS the curator: no self-rating, only the read surface. */
    isOwn: Boolean = false,
    onVote: (Int) -> Unit = {},
    /** #696 — complain about someone else's collection; null hides the action. */
    onReport: (() -> Unit)? = null,
    /** #696 — the author's only action on a HIDDEN own collection. */
    onDelete: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("public_collection_content")
    ) {
        Text(
            text = collection.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
                .testTag("public_collection_title")
        )
        Text(
            text = stringResource(R.string.collection_by_pseudonym, collection.pseudonym),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("public_collection_curator")
        )
        if (collection.description.isNotBlank()) {
            Text(
                text = collection.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("public_collection_description")
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Книг у добірці: ${collection.bookIds.size}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("public_collection_count")
        )
        // #694 — the real average with its vote count, or an honest absence:
        // nobody rated it, so nobody is shown a fabricated zero.
        val average = CollectionRating.average(collection.ratingSum, collection.ratingCount)
        if (average != null) {
            val votes = pluralStringResource(
                R.plurals.library_rating_votes,
                collection.ratingCount,
                collection.ratingCount
            )
            Text(
                text = String.format(Locale.US, "★ %.1f · %s", average, votes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("public_collection_average")
            )
        } else {
            Text(
                text = stringResource(R.string.collection_no_ratings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("public_collection_no_ratings")
            )
        }
        if (isOwn && collection.hidden) {
            // #696 — the author sees WHY their collection is gone and gets no
            // save/fork action on it; the community verdict is final.
            Text(
                text = stringResource(R.string.collection_hidden_by_reports),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("public_collection_hidden_notice")
            )
            if (onDelete != null) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .testTag("public_collection_delete")
                ) {
                    Text(stringResource(R.string.collection_delete))
                }
            }
        }
        if (!isOwn) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.collection_your_rating),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("public_collection_your_rating")
            )
            ReviewStarsRow(
                rating = myStars ?: 0,
                interactive = true,
                onRatingChange = onVote
            )
            if (onReport != null) {
                TextButton(
                    onClick = onReport,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .testTag("public_collection_report")
                ) {
                    Text(stringResource(R.string.collection_report))
                }
            }
        }
        if (!(isOwn && collection.hidden)) {
            Spacer(Modifier.height(12.dp))
            SaveCollectionForYouButton(
                originalAvailableLocally = originalAvailableLocally,
                onSave = onSaveForYou,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}
