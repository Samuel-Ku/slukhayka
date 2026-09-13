package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.collections.PublishedCollection

/**
 * Spec-51 (#692) — someone else's VISIBLE collection, as a listener reads it.
 *
 * It is deliberately read-only: the composition is shown with its curator's
 * pseudonym (never a uid), and the only action is «Зберегти собі» — which
 * creates the reader's OWN local copy (#695). Editing belongs to the owner.
 */
@Composable
fun PublicCollectionContent(
    collection: PublishedCollection,
    originalAvailableLocally: Boolean,
    onSaveForYou: () -> Unit,
    modifier: Modifier = Modifier
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
            text = "добірка слухача ${collection.pseudonym}",
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
        Spacer(Modifier.height(12.dp))
        SaveCollectionForYouButton(
            originalAvailableLocally = originalAvailableLocally,
            onSave = onSaveForYou,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))
    }
}
