package com.slukhayka.audiobooks.ui.screens.collections

import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R

/** One row of the «Мої добірки» block — already resolved by the caller. */
data class MyCollectionRow(
    val id: String,
    val title: String,
    val bookCount: Int,
    /** The first real cover of the composition, or null — honestly no cover. */
    val coverUrl: String? = null
)

/**
 * Spec-51 (#690) — «Мої добірки» in the Library. Local-first and honest: with
 * no collections it says so (no invented rows), and each row carries the real
 * title and book count.
 */
@Composable
fun MyCollectionsBlock(
    rows: List<MyCollectionRow>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // UI: порожній блок не показуємо взагалі. Раніше тут висіло
    // «Мої добірки» + «Ще немає добірок» і займало два рядки на екрані,
    // нічого не пропонуючи.
    if (rows.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("my_collections_block")
    ) {
        Text(
            text = stringResource(R.string.my_collections_title),
            style = MaterialTheme.typography.titleMedium,
            // The AC asks for real headings: a screen reader must announce this
            // as a heading, not as one more line of text.
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onOpen(row.id) }
                        .testTag("my_collection_row_${row.id}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The cover is the first REAL cover of the composition, or
                    // honestly nothing — never a placeholder pretending to be one.
                    row.coverUrl?.let { url ->
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.small)
                        ) {
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = row.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = pluralStringResource(
                                R.plurals.book_count,
                                row.bookCount,
                                row.bookCount
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
