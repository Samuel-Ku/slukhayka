package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.MainViewModel
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

    Scaffold(
        topBar = {
            // Host Scaffold in MainActivity already consumed the status bar
            // (innerPadding.top); don't let this inner TopAppBar add it again.
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = "Колекції",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
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
        // No matched collections: a sensible placeholder, never a crash —
        // the union may simply not have synced yet (same idiom as the other
        // catalogue index screens).
        Box(
            modifier = modifier.testTag("collections_index_screen"),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Колекції з'являться після завантаження каталогу.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.testTag("collections_index_screen"),
        contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp)
    ) {
        collections.forEach { collection ->
            item(key = "header_${collection.id}") {
                CatalogRowHeader(title = collection.name)
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
