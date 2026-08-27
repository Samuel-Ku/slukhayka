package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.IndexEmptyState
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.theme.*

/**
 * spec-28 (#190) — the «Колекції» index: every matched smart collection
 * (Нобелівські лауреати, Шевченківська премія, Букер, live lists) with its
 * books. Pushed from the Огляд nav row; tapping a book resolves-and-plays it
 * exactly like the inline collection cards — the move changes location, not
 * behaviour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsIndexScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    onBookClick: (GlobalSearchResult) -> Unit
) {
    val collections by viewModel.collectionsIndex.collectAsState()

    // spec-28 (#202): the chrome is the shared index scaffold — title, back
    // arrow, insets and container colour live in one place; only the content
    // differs per screen.
    IndexScreenScaffold(title = "Колекції", onBackClick = onBackClick) { padding ->
        CollectionsIndexContent(
            collections = collections,
            onBookClick = onBookClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

/**
 * The «Колекції» index body: one header + horizontal cover row per matched
 * collection (the same uniform look as the inline Огляд block), or a
 * no-collections placeholder. Public and stateless (pure `@Composable`
 * inputs — no ViewModel) so the snapshot seam pins both states from fixture
 * data.
 */
@Composable
fun CollectionsIndexContent(
    collections: List<CollectionMatcher.MatchedCollection>,
    onBookClick: (GlobalSearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    if (collections.isEmpty()) {
        // No matched collections: the shared index placeholder, never a crash
        // — the union may simply not have synced yet (spec-28 #202).
        IndexEmptyState(
            message = "Колекції з'являться після завантаження каталогу.",
            modifier = modifier.testTag("collections_index_screen")
        )
        return
    }

    LazyColumn(
        modifier = modifier.testTag("collections_index_screen"),
        contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp)
    ) {
        collections.forEach { collection ->
            item(key = "header_${collection.id}") {
                Box(Modifier.semantics(mergeDescendants = true) { heading() }) {
                    CatalogRowHeader(title = collection.name)
                }
            }
            item(key = "row_${collection.id}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(collection.books, key = { it.key }) { result ->
                        CollectionBookCard(
                            result = result,
                            onClick = { onBookClick(result) }
                        )
                    }
                }
            }
        }
    }
}
