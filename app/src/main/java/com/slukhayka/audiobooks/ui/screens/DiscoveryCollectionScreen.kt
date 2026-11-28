package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.facets.FacetIdentity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.components.formatRowDuration
import com.slukhayka.audiobooks.ui.theme.AppDimens

/** One existing catalogue card with the confirmed metadata used by its collection. */
data class DiscoveryCollectionBook(
    val result: GlobalSearchResult,
    val genres: Set<String> = emptySet(),
    val languages: Set<String> = (result.sources.map { it.language } + result.language)
        .filter(String::isNotBlank).toSet(),
    val completed: Boolean = false,
)

/** Full collection membership; the overview's shortened previews never feed this page. */
@Composable
fun DiscoveryCollectionScreen(
    title: String,
    books: List<DiscoveryCollectionBook>,
    onBackClick: () -> Unit,
    onBookClick: (GlobalSearchResult) -> Unit,
    onSearchAll: (String) -> Unit,
    modifier: Modifier = Modifier,
    collectionId: String = title,
) {
    var query by rememberSaveable(collectionId) { mutableStateOf("") }
    val normalizedQuery = remember(query) { FacetIdentity.normalizedText(query).orEmpty() }
    val visible = remember(books, normalizedQuery) {
        books.distinctBy { it.result.key }.filter { book ->
            normalizedQuery.isBlank() || listOf(book.result.title, book.result.author, book.result.narrator)
                .any { FacetIdentity.normalizedText(it).orEmpty().contains(normalizedQuery) }
        }
    }
    val listState = rememberLazyListState()
    IndexScreenScaffold(title = title, onBackClick = onBackClick) { padding ->
        Column(modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text(stringResource(R.string.collection1826_search_within)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .testTag("discovery_collection_search"),
            )
            TextButton(
                onClick = { onSearchAll(query) },
                modifier = Modifier.padding(horizontal = 8.dp).testTag("discovery_collection_search_all"),
            ) { Text(stringResource(R.string.collection1826_search_all)) }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).testTag("discovery_collection_list"),
                contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer),
            ) {
                items(visible, key = { it.result.key }) { book ->
                    BookRow(
                        title = book.result.title,
                        author = book.result.author,
                        coverUrl = book.result.coverImageUrl,
                        stats = book.result.durationSeconds?.takeIf { it > 0 }?.let(::formatRowDuration),
                        onClick = { onBookClick(book.result) },
                        modifier = Modifier.testTag("discovery_collection_book_${book.result.key}"),
                    )
                }
            }
        }
    }
}
